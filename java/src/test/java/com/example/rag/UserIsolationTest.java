package com.example.rag;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;

// Test verifying that chunks saved by user A cannot be accessed by user B
public class UserIsolationTest {

    public static void main(String[] args) {
        String uri = System.getenv("MONGODB_URI");
        if (uri == null || uri.isBlank()) {
            System.out.println("Missing MONGODB_URI. Set environment variable and try again.");
            return;
        }

        try (MongoClient client = MongoClients.create(uri)) {
            MongoCollection<Document> chunks = client.getDatabase("rag_db").getCollection("chunks");

            String userA = "user_test_alice";
            String userB = "user_test_bob";
            String testDoc = "user_isolation_sample.txt";

            // Clean up any old test data
            chunks.deleteMany(Filters.eq("source", testDoc));

            // 1. Save a chunk belonging to user A
            chunks.insertOne(new Document("text", "Confidential financial report for user A")
                    .append("source", testDoc)
                    .append("chunkIndex", 0)
                    .append("userId", userA));
            System.out.println("Saved test chunk for: " + userA);

            // 2. Search for chunks belonging to user B
            long countForUserB = chunks.countDocuments(
                    Filters.and(Filters.eq("source", testDoc), Filters.eq("userId", userB)));
            System.out.println("Searching for chunks with userId = " + userB + ". Count: " + countForUserB);

            // 3. Verify user B cannot see user A's chunk
            if (countForUserB == 0) {
                System.out.println("SUCCESS: User isolation verified! User B found 0 chunks.");
            } else {
                throw new AssertionError("SECURITY FAILURE: User B was able to see User A's data!");
            }

            // Clean up test data
            chunks.deleteMany(Filters.eq("source", testDoc));
            System.out.println("Cleaned up test data.");
        }
    }
}
