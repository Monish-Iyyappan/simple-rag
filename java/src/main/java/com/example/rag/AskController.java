package com.example.rag;

import com.example.rag.connection.ConnectionException;
import com.example.rag.connection.RequestCredentials;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController 
public class AskController {
    private final RagService rag;

    public AskController(RagService rag){
        this.rag = rag;
    }

    // Routes request with per-request credentials
    @PostMapping("/ask")
    public RagService.AskResponse ask(@RequestBody RagService.AskRequest request, HttpServletRequest httpRequest) throws Exception{
        RequestCredentials creds = RequestCredentials.from(httpRequest);
        return rag.ask(request.question(), request.mode(), creds.userId(), creds.mongoUri(), creds.apiKey());
    }

    // Handles connection and credential errors returning fixed error codes
    @ExceptionHandler(ConnectionException.class)
    public ResponseEntity<String> handleConnectionException(ConnectionException e){
        return ResponseEntity.status(e.getStatusCode()).body(e.getMessage());
    }

    // Fallback for general unhandled exceptions
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleError(Exception e){
        Throwable root = e;
        while(root.getCause() != null){
            root = root.getCause();
        }
        return ResponseEntity.status(500)
                .body(e.getClass().getSimpleName() + " / " + root.getClass().getSimpleName() + ": " + root.getMessage());
    }
}
