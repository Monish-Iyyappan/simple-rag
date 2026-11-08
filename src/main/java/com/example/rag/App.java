package com.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class App {
    private static final String MODEL = "gemini-embedding-001";
    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":embedContent";

    public static void main(String[] args) throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("Key not found. Check the variable name and restart VS Code.");
            return;
        }

        ObjectMapper mapper = new ObjectMapper();

        String body = mapper.writeValueAsString(Map.of(
                "content", Map.of("parts", List.of(Map.of("text", "Java is a programming language."))),
                "outputDimensionality", 768
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("Error " + response.statusCode() + ": " + response.body());
            return;
        }

        JsonNode values = mapper.readTree(response.body()).path("embedding").path("values");
        System.out.println("Dimensions: " + values.size());
        for (int i = 0; i < 5; i++) {
            System.out.println(values.get(i).asDouble());
        }
    }
}