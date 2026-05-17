package com.example.smartdoc.payment.web;

import com.example.smartdoc.payment.config.PaymentProperties;
import com.example.smartdoc.payment.security.StripeSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentProperties properties;

    @Test
    void checkoutSessionIsIdempotent() throws Exception {
        String request = """
            {
              "userId": "user-123",
              "planId": "pro-monthly",
              "idempotencyKey": "idem-1",
              "successUrl": "https://app.example.com/success",
              "cancelUrl": "https://app.example.com/cancel"
            }
            """;

        String first = mockMvc.perform(post("/api/v1/payments/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entitlementActive").value(false))
            .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/payments/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String firstSessionId = objectMapper.readTree(first).get("sessionId").asText();
        String secondSessionId = objectMapper.readTree(second).get("sessionId").asText();

        org.junit.jupiter.api.Assertions.assertEquals(firstSessionId, secondSessionId);
    }

    @Test
    void webhookActivationUpdatesEntitlement() throws Exception {
        String checkout = mockMvc.perform(post("/api/v1/payments/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "user-456",
                      "idempotencyKey": "idem-2"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String sessionId = objectMapper.readTree(checkout).get("sessionId").asText();
        String payload = """
            {
              "id": "evt_1",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "%s",
                  "metadata": {
                    "user_id": "user-456",
                    "plan_id": "pro-monthly"
                  }
                }
              }
            }
            """.formatted(sessionId);
        String signature = StripeSignatureVerifier.buildSignatureHeader(payload.getBytes(StandardCharsets.UTF_8), properties.stripeWebhookSecret(), Instant.now());

        mockMvc.perform(post("/api/v1/payments/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("Stripe-Signature", signature))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processed").value(true))
            .andExpect(jsonPath("$.entitlementStatus").value("active"));

        mockMvc.perform(get("/api/v1/payments/entitlement/user-456"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/v1/payments/status/user-456"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestCheckout.status").value("completed"));
    }

    @Test
    void duplicateWebhookIsIgnored() throws Exception {
        String checkout = mockMvc.perform(post("/api/v1/payments/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "user-789",
                      "idempotencyKey": "idem-3"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String sessionId = objectMapper.readTree(checkout).get("sessionId").asText();
        String payload = """
            {
              "id": "evt_2",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "%s",
                  "metadata": {
                    "user_id": "user-789",
                    "plan_id": "pro-monthly"
                  }
                }
              }
            }
            """.formatted(sessionId);
        String signature = StripeSignatureVerifier.buildSignatureHeader(payload.getBytes(StandardCharsets.UTF_8), properties.stripeWebhookSecret(), Instant.now());

        mockMvc.perform(post("/api/v1/payments/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("Stripe-Signature", signature))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/v1/payments/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("Stripe-Signature", signature))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true))
            .andExpect(jsonPath("$.processed").value(false));
    }

    @Test
    void invalidSignatureIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Stripe-Signature", "t=1,v1=bad-signature"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refundRequiresInternalToken() throws Exception {
        mockMvc.perform(post("/api/v1/payments/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "user-321",
                      "reason": "requested by user"
                    }
                    """))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/payments/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "user-321",
                      "reason": "requested by user"
                    }
                    """
                )
                .header("X-Internal-Token", properties.internalToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refunded").value(true));
    }
}

