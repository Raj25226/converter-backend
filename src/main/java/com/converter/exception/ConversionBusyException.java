package com.converter.exception;

public class ConversionBusyException extends AppException {
    public ConversionBusyException() {
        super("Conversion workers are busy. Please try again shortly.", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }
}
