package com.example.smartdoc.payment.model;

import java.time.Instant;
import java.util.Map;

public record CheckoutSession(
    String sessionId,
    String userId,
    String planId,
    String status,
    String checkoutUrl,
    String idempotencyKey,
    Map<String, String> metadata,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt
) {}

