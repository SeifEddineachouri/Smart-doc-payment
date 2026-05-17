package com.example.smartdoc.payment.security;

public class StripeSignatureVerificationException extends RuntimeException {
    public StripeSignatureVerificationException(String message) {
        super(message);
    }
}

