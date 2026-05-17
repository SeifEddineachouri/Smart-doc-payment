package com.example.smartdoc.payment.model;

import java.time.Instant;
import java.util.Map;

public record Entitlement(
    String userId,
    String planId,
    String status,
    boolean active,
    String source,
    Instant updatedAt,
    Instant expiresAt,
    Map<String, Object> metadata
) {}

