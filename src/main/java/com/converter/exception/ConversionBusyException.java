package com.converter.exception;

import org.springframework.http.HttpStatus;

public class ConversionBusyException extends AppException {
    public ConversionBusyException() {
        super(
                "Conversion workers are busy. Please try again shortly.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
