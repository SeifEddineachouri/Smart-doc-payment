package com.example.smartdoc.payment.web;

import java.time.Instant;

public record ApiErrorResponse(String message, Instant timestamp) {}

