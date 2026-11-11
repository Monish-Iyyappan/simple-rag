package com.example.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class chunker {
    public static List<String> chunk(String text, int size, int overlap){
        List<String> chunks = new ArrayList<>();
        text = text.replace("\\s+", " ").trim();
        String[] sentences = text.split("(?<=[.!?]) ");

        StringBuilder current = new StringBuilder();
        String lastSentence = "";

        for (String s : sentences) {
            if (current.length() > 0 && current.length() + s.length() + 1 > size) {
                chunks.add(current.toString());
                current = new StringBuilder(lastSentence);
            }
            if(current.length() > 0){
                current.append(" ");
            }
            current.append(s);
            lastSentence = s;
        }
        if(current.length() > 0){
            chunks.add(current.toString());
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
