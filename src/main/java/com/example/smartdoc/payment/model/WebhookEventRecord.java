package com.example.smartdoc.payment.model;

import java.time.Instant;

public record WebhookEventRecord(
    String eventId,
    String eventType,
    boolean processed,
    boolean duplicate,
    String payloadJson,
    Instant processedAt
) {}

