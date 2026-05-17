package com.example.smartdoc.payment.web.dto;

public record PaymentStatusResponse(
    String userId,
    EntitlementResponse entitlement,
    CheckoutSessionResponse latestCheckout
) {}

