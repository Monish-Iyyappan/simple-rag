package com.example.rag;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

public class search {
    public static void main(String[] args) throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        String uri = System.getenv("MONGODB_URI");
        if(key == null || key.isBlank() || uri == null || uri.isBlank()){
            System.out.println("Missing GEMINI_API_KEY or MONGODB_URI. Restart VS Code and try again.");
            return;
        }

        String question = "Where are the embedding stored?";

        double[] vector = App.embed(question, key);
        List<Double> queryVector = new ArrayList<>();
        for(double v : vector) {
            queryVector.add(v);
        }

        List<Document> pipeline = List.of(
            new Document("$vectorSearch", new Document("index", "autoembed_index")
                .append("path", "embedding")
                .append("queryVector", queryVector)
                .append("numCandidates", 50)
                .append("limit", 3)),
            new Document("$project", new Document("text", 1)
                .append("source", 1)
                .append("chunkIndex", 1)
                .append("score", new Document("$meta", "vectorSearchScore")))
        );

        try(MongoClient client = MongoClients.create(uri)){
            MongoCollection<Document> chunks = client.getDatabase("rag_db").getCollection("chunks");

            System.out.println("Question: " + question);
            int rank = 1;
            for(Document d : chunks.aggregate(pipeline)){
                System.out.println("\n#" + rank++ + " score=" + d.get("score") + " chunk "
                    + d.get("chunkIndex") + " of " + d.get("source"));
                System.out.println(d.getString("text"));
            }
        }
     }
}
