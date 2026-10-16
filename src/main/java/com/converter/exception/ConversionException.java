package com.converter.exception;

import org.springframework.http.HttpStatus;

public class ConversionException extends AppException {

    public ConversionException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
