import os,re,sys,time, requests
from pymongo import MongoClient

BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
EMBED_MODEL = "gemini-embedding-001"
ANSWER_MODEL = "gemini-3.5-flash-lite"
DIMENSIONS = 768
INDEX_NAME = "autoembed_index"

KEY = os.environ.get("GEMINI_API_KEY")
URI = os.environ.get("MONGODB_URI")
if not KEY or not URI:
    sys.exit("Missing GEMINI_API_KEY or MONGODB_URI. Open a new terminal and try again.")

chunks = MongoClient(URI, serverSelectionTimeoutMS=8000)["rag_db"]["chunks"]

def chunk(text, size=300):
    #Split into whole txt into chunks with embed vectors
    text = re.sub(r"\s+", " ", text).strip()
    sentences = re.split(r"(?<=[.!?]) ", text)
    result, current, last = [], "", ""
    for s in sentences:
        if current and len(current) + len(s) + 1 > size:
            result.append(current)
            current = last  # overlap: last sentence of the previous chunk
        current = (current + " " + s) if current else s
        last = s
    if current:
        result.append(current)
    return result

def embed(text):
    r = requests.post(
        BASE + EMBED_MODEL + ":embedContent",
        headers = {"x-goog-api-key": KEY},
        json = {
            "content": {"parts": [{"text": text}]},
            "outputDimensionality": DIMENSIONS,
        },
        timeout=30,
    )
    r.raise_for_status()
    return r.json()["embedding"]["values"]

def ingest(path, user_id):
    source = os.path.basename(path)
    with open(path, encoding="utf-8") as f:
        pieces = chunk(f.read())
    chunks.delete_many({"source": source, "userId": user_id}) #re-ingesting replace old chunks
    for i, piece in enumerate(pieces):
        chunks.insert_one({
            "text": piece,
            "embedding": embed(piece),
            "source": source,
            "chunkIndex": i,
            "userId": user_id,
        })
        time.sleep(0.3)
        print(f"stored chunk {i + 1}/{len(pieces)}", flush=True)
    return len(pieces)

def search(question, k=3, user_id=None):
    pipeline = [
        {"$vectorSearch": {
            "index": INDEX_NAME,
            "path": "embedding",
            "queryVector": embed(question),
            "numCandidates": 50,
            "limit": k,
            "filter": {"userId": user_id},
        }},
        {"$project": {
            "text": 1, "source": 1, "chunkIndex": 1,
            "score": {"$meta": "vectorSearchScore"},
        }},
    ]
    return list(chunks.aggregate(pipeline))

def answer(question, mode="hybrid", user_id=None):
    hits = hybrid_search(question, 3, user_id) if mode == "hybrid" else search(question, user_id)
    context = "\n".join("- " + h["text"] for h in hits)
    prompt = (
        "Answer the question ONLY the context below, in at most 3 sentences. "
        "If the answer is not in the context, say \"I don't know based on the documents.\"\n\n"
        f"Context:\n{context}\n\nQuestion: {question}"
    )
    for attempt in range(4):
        r = requests.post(
            BASE + ANSWER_MODEL + ":generateContent",
            headers={"x-goog-api-key": KEY},
            json={"contents": [{"parts": [{"text": prompt}]}]},
            timeout=60,
        )
        if r.status_code in (429, 503) and attempt < 3:
            print(f"Gemini busy ({r.status_code}), retrying in {(attempt + 1) * 5}s...")
            time.sleep((attempt + 1) * 5)
            continue
        break
    r.raise_for_status()
    text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
    return text, hits

def keyword_search(question, k=10, user_id=None):
    pipeline = [
        {"$search": {"index": "text_index", "compound":{
            "must": [{"text": {"query": question, "path": "text"}}],
            "filter": [{"equals": {"path": "userId", "value": user_id}}],
        }}},
        {"$limit": k},
        {"$project": {"text":1, "source":1, "chunkIndex": 1}},
    ]

    return list(chunks.aggregate(pipeline))

def hybrid_search(question, k=3, user_id=None):
    vector_hits = search(question, 10, user_id)
    keyword_hits = keyword_search(question, 10, user_id)
    scores, docs = {}, {}
    for results in (vector_hits, keyword_hits):
        for rank, d in enumerate(results, start=1):
            docs[d["_id"]] = d
            scores[d["_id"]] = scores.get(d["_id"], 0) + 1 / (60 + rank)
    best = sorted(scores, key=scores.get, reverse=True)[:k]
    return [dict(docs[i], score=scores[i]) for i in best]

if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "ingest":
        file_path = sys.argv[2]
        user = sys.argv[3]
        print(f"Stored {ingest(file_path, user)} chunks for user '{user}'")
    elif len(sys.argv) >= 4 and sys.argv[1] == "ask":
        user = sys.argv[2]
        args = sys.argv[3:]
        mode = "vector" if "--vector" in args else "hybrid"
        words = [w for w in args if w != "--vector"]
        question = " ".join(words)
        text, hits = answer(question, mode, user)
        print(f"\nUser: {user} | Mode: {mode}")
        print("\nAnswer: ", text)
        print("\nSources:")
        for h in hits:
            print(f" - ({h.get('score',0):.3f}) {h['text']}")
    else:
        print('Usuage:\n python rag.py ingest <file.text> <user_id>\n python rag.py ask <user_id> "your question" [--vector]')
    