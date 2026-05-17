package com.example.smartdoc.payment.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CheckoutSessionCreateRequest(
    @NotBlank String userId,
    String planId,
    String idempotencyKey,
    String successUrl,
    String cancelUrl,
    @Email String customerEmail
) {}

