package com.example.smartdoc.payment.web.dto;

public record RefundResponse(
    String refundId,
    String userId,
    String status,
    boolean refunded,
    String reason
) {}

