package com.example.rag.connection;

import jakarta.servlet.http.HttpServletRequest;

// Extracts and validates incoming request headers
public record RequestCredentials(String userId, String mongoUri, String apiKey) {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_MONGO_URI = "X-Mongo-Uri";
    public static final String HEADER_API_KEY = "X-Api-Key";

    // Validates presence of headers and URI safety
    public static RequestCredentials from(HttpServletRequest request) {
        String userId = request.getHeader(HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new ConnectionException("INVALID_URI", 400);
        }

        String apiKey = request.getHeader(HEADER_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConnectionException("API_KEY_REJECTED", 400);
        }

        String mongoUri = request.getHeader(HEADER_MONGO_URI);
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new ConnectionException("INVALID_URI", 400);
        }

        validateMongoUri(mongoUri.trim());
        return new RequestCredentials(userId.trim(), mongoUri.trim(), apiKey.trim());
    }

    // Ensures scheme is valid and blocks private/local IP ranges (SSRF)
    public static void validateMongoUri(String uri) {
        String lower = uri.toLowerCase();

        // Must start with mongodb:// or mongodb+srv://
        if (!lower.startsWith("mongodb://") && !lower.startsWith("mongodb+srv://")) {
            throw new ConnectionException("INVALID_URI", 400);
        }

        // Block localhost and loopback
        if (lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("::1") || lower.contains("0.0.0.0")) {
            throw new ConnectionException("INVALID_URI", 400);
        }

        // Block private IP ranges (10.*, 192.168.*, 169.254.*, 172.16-31.*)
        if (lower.matches(".*\\b(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|169\\.254\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d{1,3}\\.\\d{1,3})\\b.*")) {
            throw new ConnectionException("INVALID_URI", 400);
        }
    }
}
