package com.example.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class answer {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private static void main(String[] args) throws Exception{
        String key = System.getenv("GEMINI_API_KEY");
        String uri = System.getenv("MONGODB_URI");
        if(key == null || key.isBlank() || uri == null || uri.isBlank()){
            System.out.println("Missing GEMINI_API_KEY or MONGODB_URI. Restart VS Code and try again.");
            return;
        }

        String question = "How does RAG reduce made-up answer?";

        //1.embed the question  
        List<Double> queryVector = new ArrayList<>();
        for(double v : App.embed(question, key)){
            queryVector.add(v);
        }          

        //2.retrieve the top chunks
        List<Document> pipeline = List.of(
            new Document("$vectorSearch", new Document("index", "autoembed_index")
                .append("path", "embedding")
                .append("queryVector", queryVector)
                .append("numCandidates", 50)
                .append("limit", 3)),
            new Document("$project", new Document("text", 1))
        );

        StringBuilder context = new StringBuilder();
        try(MongoClient client = MongoClients.create(uri)){
            for(Document d : client.getDatabase("rag_db").getCollection("chunks").aggregate(pipeline)){
                context.append("- ").append(d.getString("text")).append("\n");
            }
        }

        //3.build the prompt
        String prompt = "Answer the question using ONLY the context below, in at most 2 short sentences. "
                + "If the answer is not in the context, say \"I don't know based on the documents.\"\n\n"
                + "context:\n" + context + "\nQuestion: " + question;

        //4.ask gemini
        ObjectMapper mapper = new ObjectMapper();
        String body = mapper.writeValueAsString(Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long t0 = System.currentTimeMillis();
        
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        for(int attempt = 1; attempt <= 3 && (response.statusCode() == 503 || response.statusCode() == 429); attempt++){
            System.out.println("Busy (" + response.statusCode() + "), retrying in " + (attempt * 5) + "s...");
            Thread.sleep(attempt*5000L);
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        System.out.println("Gemini took " + (System.currentTimeMillis() - t0) + " ms");

        if(response.statusCode() != 200){
            System.out.println("Error " + response.statusCode() + ": " + response.body());
            return;
        }

        String answer = mapper.readTree(response.body())
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
        
        System.out.println("Question: " + question);
        System.out.println("\nContext used:\n" + context);
        System.out.println("Answer:\n" + answer);
    }
}
