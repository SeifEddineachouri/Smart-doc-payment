package com.example.smartdoc.payment.web.dto;

public record WebhookResponse(
    String eventId,
    String eventType,
    boolean processed,
    boolean duplicate,
    String entitlementStatus,
    String userId
) {}

