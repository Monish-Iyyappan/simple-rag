package com.example.rag.connection;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;

import java.util.concurrent.TimeUnit;

// Builds MongoClient per request and pings with a 5-second timeout
public class MongoConnection {

    // Creates client with 5s timeout and pings 'admin' database
    public static MongoClient createAndPing(String mongoUri) {
        try {
            MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongoUri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .build();

            MongoClient client = MongoClients.create(settings);
            // Run ping command to verify connection immediately
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return client;
        } catch (MongoSecurityException e) {
            System.err.println("Error: MONGO_AUTH_FAILED");
            throw new ConnectionException("MONGO_AUTH_FAILED", 400);
        } catch (MongoTimeoutException e) {
            System.err.println("Error: MONGO_UNREACHABLE");
            throw new ConnectionException("MONGO_UNREACHABLE", 502);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: INVALID_URI");
            throw new ConnectionException("INVALID_URI", 400);
        } catch (Exception e) {
            String msg = (e.getMessage() != null) ? e.getMessage().toLowerCase() : "";
            if (msg.contains("auth") || msg.contains("credential")) {
                System.err.println("Error: MONGO_AUTH_FAILED");
                throw new ConnectionException("MONGO_AUTH_FAILED", 400);
            }
            System.err.println("Error: MONGO_UNREACHABLE");
            throw new ConnectionException("MONGO_UNREACHABLE", 502);
        }
    }
}
