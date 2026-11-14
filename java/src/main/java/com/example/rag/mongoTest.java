package com.example.rag;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

public class mongoTest {
    public static void main(String[] args){
        String uri = System.getenv("MONGODB_URI");
        if(uri == null || uri.isBlank()){
            System.out.println("URI not found. Check the variable name and  restart VS Code.");
            return;
        }

        try (MongoClient client = MongoClients.create(uri)){
            MongoCollection<Document> chunks = client.getDatabase("rag_db").getCollection("chunks");

            chunks.insertOne(new Document("text", "test chunk").append("source", "manual-test"));
            System.out.println("Inserted. Documents in collection: " + chunks.countDocuments());
        }
    }
}
