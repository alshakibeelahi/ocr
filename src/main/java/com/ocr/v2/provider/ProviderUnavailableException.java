package com.ocr.v2.provider;

/** The requested chat provider is unknown, disabled, or failed to initialise. Maps to HTTP 400. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
