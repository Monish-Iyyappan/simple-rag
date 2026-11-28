package com.example.rag.connection;

public class ConnectionException extends RuntimeException{
    private final int statusCode;

    public ConnectionException(String errorCode, int statusCode){
        super(errorCode);
        this.statusCode = statusCode;
    }

    public int getStatusCode(){
        return statusCode;
    }
}