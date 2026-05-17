package com.example.smartdoc.payment.model;

public record Plan(
    String id,
    String name,
    int priceCents,
    String currency,
    String interval,
    boolean active
) {}

