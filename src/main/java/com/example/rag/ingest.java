package com.example.rag;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ingest {
    public static void main(String[] args) throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        String uri = System.getenv("MONGODB_URI");
        if(key == null || key.isBlank() || uri == null || uri.isBlank()){
            System.out.println("Missing GEMINI_API_KEY or MONGODB_URI. Restart VS Code andtry again.");
            return;
        }

        String source = "sample.txt";
        String text = Files.readString(Path.of("docs/" + source));
        List<String> pieces = chunker.chunk(text, 300, 50);

        try(MongoClient client = MongoClients.create(uri)){
            MongoCollection<Document> chunks = client.getDatabase("rag_db").getCollection("chunks");

            //remove old document to avoid duplicates when rerunning
            chunks.deleteMany(Filters.eq("source", source));

            for(int i = 0; i < pieces.size(); i++){
                double[] vector = App.embed(pieces.get(i), key);

                List<Double> embedding = new ArrayList<>();
                for(double v : vector){
                    embedding.add(v);
                }

                chunks.insertOne(new Document("text", pieces.get(i))
                        .append("embedding", embedding)
                        .append("source", source)
                        .append("chunkIndex", i));

                System.out.println("Stored chunk " + i);
                Thread.sleep(500);
            }
            System.out.println("Done. Documents in collection: " + chunks.countDocuments());
        }
    }
}
