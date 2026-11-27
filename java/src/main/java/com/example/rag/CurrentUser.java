package com.example.rag;

import jakarta.servlet.http.HttpServletRequest;

public class CurrentUser {
    public static final String HEADER_USER_ID = "X-User-Id";

    public static String getUserId(HttpServletRequest request){
        String userId = request.getHeader(HEADER_USER_ID);

        if(userId == null || userId.isBlank()){
            throw new IllegalArgumentException("Missing required X-User-Id header");
        }
        return userId.trim();
    }
}
