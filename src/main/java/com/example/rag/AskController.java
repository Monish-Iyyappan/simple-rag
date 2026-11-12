package com.example.rag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController 
public class AskController {
    private final RagService rag;

    public AskController(RagService rag){
        this.rag = rag;
    }

    @PostMapping("/ask")
    public RagService.AskResponse ask(@RequestBody RagService.AskRequest request) throws Exception{
        return rag.ask(request.question());
    }
}
