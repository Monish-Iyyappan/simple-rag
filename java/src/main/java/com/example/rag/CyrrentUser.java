package com.example.rag;

import jakarta.service.http.HttpServletRequest;

public class CyrrentUser {
    public static final String HEADER_USER_ID = "X-User-Id";

    public static String getUserId(KttpServletRequest request){
        String userId = request.getHeader(HEADER_USER_ID);

        if(UserId == null || userId.isBlank()){
            throw new IllegalArgumentException("Missing required X-User-Id header");
        }
        return userId.trim();
    }
}
