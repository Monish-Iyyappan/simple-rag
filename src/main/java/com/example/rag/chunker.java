package com.example.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class chunker {
    public static List<String> chunk(String text, int size, int overlap){
        List<String> chunks = new ArrayList<>();
        text = text.replace("\\s+", " ").trim();
        int step = size - overlap;

        for(int start = 0; start < text.length(); start += step){
            int end = Math.min(start + size, text.length());
            chunks.add(text.substring(start, end));
            if(end == text.length()) break;
        }

        return chunks;
    }

    public static void main(String[] args) throws Exception{
        String text = Files.readString(Path.of("docs/sample.txt"));
        List<String> chunks = chunk(text, 300, 50);

        System.out.println("Total chunks: " + chunks.size());
        for(int i = 0; i < chunks.size(); i++){
            System.out.println("--- chunk " + i + " (" + chunks.get(i).length() + " char) ---");
            System.out.println(chunks.get(i));
        }
    }
}
