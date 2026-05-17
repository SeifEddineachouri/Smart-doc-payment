package com.example.smartdoc.payment.web;

import com.example.smartdoc.payment.service.PaymentService;
import com.example.smartdoc.payment.web.dto.CheckoutSessionCreateRequest;
import com.example.smartdoc.payment.web.dto.CheckoutSessionResponse;
import com.example.smartdoc.payment.web.dto.EntitlementResponse;
import com.example.smartdoc.payment.web.dto.PaymentStatusResponse;
import com.example.smartdoc.payment.web.dto.RefundRequest;
import com.example.smartdoc.payment.web.dto.RefundResponse;
import com.example.smartdoc.payment.web.dto.WebhookResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/checkout-session")
    public CheckoutSessionResponse createCheckoutSession(@Valid @RequestBody CheckoutSessionCreateRequest request) {
        return paymentService.createCheckoutSession(request);
    }

    @GetMapping("/entitlement/{userId}")
    public EntitlementResponse getEntitlement(@PathVariable String userId) {
        return paymentService.getEntitlement(userId);
    }

    @GetMapping("/status/{userId}")
    public PaymentStatusResponse getStatus(@PathVariable String userId) {
        return paymentService.getStatus(userId);
    }

    @PostMapping(value = "/webhooks/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WebhookResponse stripeWebhook(@RequestBody byte[] payload, @RequestHeader("Stripe-Signature") String signatureHeader) {
        return paymentService.handleStripeWebhook(payload, signatureHeader);
    }

    @PostMapping("/refund")
    public RefundResponse refund(
        @Valid @RequestBody RefundRequest request,
        @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        return paymentService.refund(request, internalToken);
    }
}

