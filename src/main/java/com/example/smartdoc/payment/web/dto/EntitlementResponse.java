package com.example.smartdoc.payment.web.dto;

import java.time.Instant;

public record EntitlementResponse(
    String userId,
    String planId,
    String status,
    boolean active,
    Instant updatedAt,
    Instant expiresAt
) {}

