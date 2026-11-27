package com.example.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Duration;
import com.mongodb.client.model.Filters;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagService {
    
    public record AskRequest(String question, String mode) {}
    public record AskResponse(String answer, List<String> sources){}

    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final MongoClient client;
    private final String geminikey;
    private final HybridSearch hybridSearch = new HybridSearch();

    public RagService(){
        this.geminikey = System.getenv("GEMINI_API_KEY");
        String uri = System.getenv("MONGODB_URI");
        if (geminikey == null || geminikey.isBlank() || uri == null || uri.isBlank()) {
            throw new IllegalStateException("Missing GEMINI_API_KEY or MONGODB_URI");
        }
        this.client = MongoClients.create(uri);
    }

    @PreDestroy
    public void close(){
        client.close();
    }

    
    public void deleteByDocument(String docId, String userId){
        client.getDatabase("rag_db").getCollection("chunks")
            .deleteMany(Filters.and(Filters.eq("source", docId), Filters.eq("userId", userId)));
    }
    
    // Vector search on autoembed_index
    public List<Document> vectorSearch(List<Double> queryVector, int limit, String userId) {
        List<Document> pipeline = List.of(
            new Document("$vectorSearch", new Document("index", "autoembed_index")
                .append("path", "embedding")
                .append("queryVector", queryVector)
                .append("numCandidates", 50)
                .append("limit", limit)
                .append("filter", new Document("userId", userId))),
            new Document("$project", new Document("text", 1)
                .append("source", 1)
                .append("chunkIndex", 1))
        );

        List<Document> results = new ArrayList<>();
        for (Document d : client.getDatabase("rag_db").getCollection("chunks").aggregate(pipeline)) {
            results.add(d);
        }
        return results;
    }

    // Keyword search on Atlas Search text_index
    public List<Document> keywordSearch(String queryText, int limit, String userId) {
        List<Document> pipeline = List.of(
            new Document("$search", new Document("index", "text_index")
                .append("compound", new Document("must", List.of(
                    new Document("text", new Document("query", queryText).append("path", "text"))
                )).append("filter", List.of(
                    new Document("equals", new Document("path", "userId").append("value", userId))
                )))),
            new Document("$limit", limit),
            new Document("$project", new Document("text", 1)
                .append("source", 1)
                .append("chunkIndex", 1))
        );

        List<Document> results = new ArrayList<>();
        for (Document d : client.getDatabase("rag_db").getCollection("chunks").aggregate(pipeline)) {
            results.add(d);
        }
        return results;
    }

    // Backward-compatible ask method defaulting to vector mode
    public AskResponse ask(String question, String userId) throws Exception {
        return ask(question, "vector", userId);
    }

    // Answers question using either vector search or hybrid search
    public AskResponse ask(String question, String mode, String userId) throws Exception {
        // 1. Embed the question
        List<Double> queryVector = new ArrayList<>();
        for (double v : App.embed(question, geminikey)) {
            queryVector.add(v);
        }

        // 2. Default to vector search if mode is null or blank
        String searchMode = (mode != null && !mode.isBlank()) ? mode.toLowerCase() : "vector";

        // 3. Search for top chunks
        List<Document> hits;
        if ("hybrid".equals(searchMode)) {
            // Retrieve 10 candidates each and merge using HybridSearch
            List<Document> vHits = vectorSearch(queryVector, 10, userId);
            List<Document> kHits = keywordSearch(question, 10, userId);
            hits = hybridSearch.merge(vHits, kHits, 3);
        } else {
            hits = vectorSearch(queryVector, 3, userId);
        }

        List<String> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (Document d : hits) {
            String text = d.getString("text");
            sources.add(text);
            context.append("- ").append(text).append("\n");
        }

        System.out.println("Search done (" + searchMode + "), calling Gemini now");

        // 4. Build prompt
        String prompt = "Answer the question using ONLY the context below, in at most 3 sentences."
                + "If the answer is not in the context, say \"I don't know based on the documents.\"\n\n"
                + "Context:\n" + context + "\nQuestion: " + question;

        // 5. Send prompt to Gemini with retry logic
        String body = mapper.writeValueAsString(Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        ));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", geminikey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long t0 = System.currentTimeMillis();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        for (int attempt = 1; attempt <= 3 && (response.statusCode() == 503 || response.statusCode() == 429); attempt++) {
            System.out.println("Gemini busy (" + response.statusCode() + "), retry " + attempt);
            Thread.sleep(attempt * 5000L);
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        System.out.println("Gemini finished: status " + response.statusCode() + " after " + (System.currentTimeMillis() - t0) + " ms");
        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini error " + response.statusCode() + ": " + response.body());
        }

        String answer = mapper.readTree(response.body())
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();

        return new AskResponse(answer, sources);
    }

    public int ingest(String source, String text, String userId) throws Exception{
        List<String> pieces = chunker.chunk(text, 300, 50);
        var chunks = client.getDatabase("rag_db").getCollection("chunks");

        //remove old chunks of this file so re-uploading doesn't create duplicates
        deleteByDocument(source, userId);

        for(int i = 0; i < pieces.size(); i++){
            List<Double> embedding = new ArrayList<>();
            for(double v : App.embed(pieces.get(i), geminikey)){
                embedding.add(v);
            }
            chunks.insertOne(new Document("text", pieces.get(i))
                    .append("embedding", embedding)
                    .append("source", source)
                    .append("chunkIndex", i)
                    .append("userId", userId));
                Thread.sleep(300);
        }
        return pieces.size();
    }

}
