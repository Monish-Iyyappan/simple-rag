package com.example.rag;

import com.example.rag.connection.RequestCredentials;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class IngestController {

    private final RagService rag;

    public IngestController(RagService rag) {
        this.rag = rag;
    }

    // Ingests file using per-request credentials
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws Exception {
        RequestCredentials creds = RequestCredentials.from(request);
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        int count = rag.ingest(file.getOriginalFilename(), text, creds.userId(), creds.mongoUri(), creds.apiKey());
        return Map.of("source", file.getOriginalFilename(), "chunksStored", count, "userId", creds.userId());
    }
}
