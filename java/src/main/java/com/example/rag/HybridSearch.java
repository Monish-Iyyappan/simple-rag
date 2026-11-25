package com.example.rag;

import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Component
public class HybridSearch {
    private static final double RRF_K = 60.0;
       
    
    public List<Document>merge(List<Document> vectorHits, List<Document> keywordHits, int k){
        Map<Object, Double> scores = new HashMap<>();
        Map<Object, Document> docs = new HashMap<>();

        List<List<Document>> allLists = List.of(vectorHits, keywordHits);
        for(List<Document> list : allLists){
            for(int i = 0; i < list.size(); i++){
                Document doc = list.get(i);
                Object id = doc.get("_id");
                int rank = i+1;

                docs.putIfAbsent(id, doc);
                double currentScore = scores.getOrDefault(id, 0.0);
                scores.put(id, currentScore + (1.0 / (RRF_K + rank)));
            }
        } 

        List<Object> sortedIds = new ArrayList<>(scores.keySet());
        sortedIds.sort((a,b) -> Double.compare(scores.get(b), scores.get(a)));

        List<Document> results = new ArrayList<>();
        int limit = Math.min(k, sortedIds.size());
        for(int i = 0; i < limit; i++){
            Object id = sortedIds.get(i);
            Document doc = docs.get(id);
            doc.append("score", scores.get(id));
            results.add(doc);
        }

        return results;
    }
}

