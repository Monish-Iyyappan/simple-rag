package com.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import javax.management.RuntimeErrorException;

public class App {
    private static final String MODEL = "gemini-embedding-001";
    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":embedContent";

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient client = HttpClient.newHttpClient();

    static double[] embed(String text, String key) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "outputDimensionality", 768
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Error " + response.statusCode() + ": " + response.body());
        }

        JsonNode values = mapper.readTree(response.body()).path("embedding").path("values");
        double[] vector = new double[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i).asDouble();
        }
        return vector;
    }

    static double cosine(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static void main(String[] args) throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("Key not found. Check the variable name and restart VS Code.");
            return;
        }

        double[] java = embed("Java is a programming language.", key);
        double[] python = embed("Python is used to write software.", key);
        double[] mango = embed("I love eating mango ice cream.", key);

        System.out.println("Java vs Python: " + cosine(java, python));
        System.out.println("Java vs Mango: " + cosine(java, mango));
    }
}