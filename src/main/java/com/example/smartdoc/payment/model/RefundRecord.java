package com.example.smartdoc.payment.model;

import java.time.Instant;

public record RefundRecord(
    String refundId,
    String userId,
    String reason,
    String status,
    Instant createdAt
) {}

