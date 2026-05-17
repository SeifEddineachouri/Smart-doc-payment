package com.example.smartdoc.payment.web.dto;

public record CheckoutSessionResponse(
    String sessionId,
    String checkoutUrl,
    String status,
    String userId,
    String planId,
    String planName,
    int amountCents,
    String currency,
    boolean entitlementActive,
    String message
) {}

