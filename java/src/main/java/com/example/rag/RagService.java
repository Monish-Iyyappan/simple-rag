package com.example.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.rag.connection.ConnectionException;
import com.example.rag.connection.MongoConnection;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagService {
    
    // Request record with question and optional mode ("vector" or "hybrid")
    public record AskRequest(String question, String mode) {}
    public record AskResponse(String answer, List<String> sources) {}

    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final HybridSearch hybridSearch = new HybridSearch();

    public RagService() {
        // Clients and credentials are built per request
    }

    // Deletes all chunks for a document belonging to a specific user
    public void deleteByDocument(MongoClient client, String docId, String userId) {
        client.getDatabase("rag_db").getCollection("chunks")
            .deleteMany(Filters.and(Filters.eq("source", docId), Filters.eq("userId", userId)));
    }
    
    // Vector search on autoembed_index filtered strictly by userId
    public List<Document> vectorSearch(MongoClient client, List<Double> queryVector, int limit, String userId) {
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

    // Keyword search on Atlas Search text_index filtered strictly by userId
    public List<Document> keywordSearch(MongoClient client, String queryText, int limit, String userId) {
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

    // Merges vector and keyword search results for a specific user
    public List<Document> hybridSearch(MongoClient client, List<Double> queryVector, String question, int k, String userId) {
        List<Document> vHits = vectorSearch(client, queryVector, 10, userId);
        List<Document> kHits = keywordSearch(client, question, 10, userId);
        return hybridSearch.merge(vHits, kHits, k);
    }

    // Answers question using per-request credentials
    public AskResponse ask(String question, String mode, String userId, String mongoUri, String apiKey) throws Exception {
        // 1. Embed the question using the request's API key
        List<Double> queryVector = new ArrayList<>();
        for (double v : App.embed(question, apiKey)) {
            queryVector.add(v);
        }

        // 2. Default to vector search if mode is null or blank
        String searchMode = (mode != null && !mode.isBlank()) ? mode.toLowerCase() : "vector";

        // 3. Search for top chunks using per-request MongoClient (with 5-second ping)
        List<Document> hits;
        try (MongoClient client = MongoConnection.createAndPing(mongoUri)) {
            if ("hybrid".equals(searchMode)) {
                hits = hybridSearch(client, queryVector, question, 3, userId);
            } else {
                hits = vectorSearch(client, queryVector, 3, userId);
            }
        }

        List<String> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (Document d : hits) {
            String text = d.getString("text");
            sources.add(text);
            context.append("- ").append(text).append("\n");
        }

        System.out.println("Search done (" + searchMode + ") for user " + userId + ", calling Gemini now");

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
                .header("x-goog-api-key", apiKey)
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

        // Check for rejected API key
        if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403) {
            System.err.println("Error: API_KEY_REJECTED");
            throw new ConnectionException("API_KEY_REJECTED", 400);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini error " + response.statusCode() + ": " + response.body());
        }

        String answer = mapper.readTree(response.body())
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();

        return new AskResponse(answer, sources);
    }

    // Ingests text into chunks using per-request credentials
    public int ingest(String source, String text, String userId, String mongoUri, String apiKey) throws Exception {
        List<String> pieces = chunker.chunk(text, 300, 50);

        // Build and ping Mongo client per request
        try (MongoClient client = MongoConnection.createAndPing(mongoUri)) {
            var chunks = client.getDatabase("rag_db").getCollection("chunks");
            deleteByDocument(client, source, userId);

            for (int i = 0; i < pieces.size(); i++) {
                List<Double> embedding = new ArrayList<>();
                for (double v : App.embed(pieces.get(i), apiKey)) {
                    embedding.add(v);
                }
                chunks.insertOne(new Document("text", pieces.get(i))
                        .append("embedding", embedding)
                        .append("source", source)
                        .append("chunkIndex", i)
                        .append("userId", userId));
                Thread.sleep(300);
            }
        }
        return pieces.size();
    }
}
