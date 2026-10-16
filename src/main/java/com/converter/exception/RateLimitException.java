package com.converter.exception;

import org.springframework.http.HttpStatus;

public class RateLimitException extends AppException {

    public RateLimitException(String message) {
        super(message, HttpStatus.TOO_MANY_REQUESTS);
    }
}
