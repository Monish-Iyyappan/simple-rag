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
    
    public record AskRequest(String question) {}
    public record AskResponse(String answer, List<String> sources){}

    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final MongoClient client;
    private final String geminikey;

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

    public AskResponse ask(String question) throws Exception{
        //1.embed the question
        List<Double> queryVector = new ArrayList<>();
        for(Double v : App.embed(question, geminikey)){
            queryVector.add(v);
        }

        //2.retrieve the top chunk.
        List<Document> pipeline = List.of(
            new Document("$vectorSearch", new Document("index", "autoembed_index")
                        .append("path", "embedding")
                        .append("queryVector", queryVector)
                        .append("numCandidates", 50)
                        .append("limit", 3)),
            new Document("$project", new Document("text", 1))
        );

        List<String> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for(Document d : client.getDatabase("rag_db").getCollection("chunks").aggregate(pipeline)){
            sources.add(d.getString("text"));
            context.append("- ").append(d.getString("text")).append("\n");
        }

        System.out.println("Search done, calling Gemini now");
        //3. build the prompt
        String prompt = "Answer the question using ONLY the context below, in at most 3 sentences."
                + "If the answer is not in the context, say \"I don't know based on the documents.\"\n\n"
                + "Context:\n" + context + "\nQuestion: " + question;

        //4. ask gemini (retry when busy)
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

    public int ingest(String source, String text) throws Exception{
        List<String> pieces = chunker.chunk(text, 300, 50);
        var chunks = client.getDatabase("rag_db").getCollection("chunks");

        //remove old chunks of this file so re-uploading doesn't create duplicates
        chunks.deleteMany(Filters.eq("source", source));

        for(int i = 0; i < pieces.size(); i++){
            List<Double> embedding = new ArrayList<>();
            for(double v : App.embed(pieces.get(i), geminikey)){
                embedding.add(v);
            }
            chunks.insertOne(new Document("text", pieces.get(i))
                    .append("embedding", embedding)
                    .append("source", source)
                    .append("chunkIndex", i));
                Thread.sleep(300);
        }
        return pieces.size();
    }

}
