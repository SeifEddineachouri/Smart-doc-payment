package com.example.smartdoc.payment.service;

import com.example.smartdoc.payment.config.PaymentProperties;
import com.example.smartdoc.payment.model.CheckoutSession;
import com.example.smartdoc.payment.model.Entitlement;
import com.example.smartdoc.payment.model.Plan;
import com.example.smartdoc.payment.model.RefundRecord;
import com.example.smartdoc.payment.model.WebhookEventRecord;
import com.example.smartdoc.payment.repository.PaymentRepository;
import com.example.smartdoc.payment.security.StripeSignatureVerifier;
import com.example.smartdoc.payment.web.dto.CheckoutSessionCreateRequest;
import com.example.smartdoc.payment.web.dto.CheckoutSessionResponse;
import com.example.smartdoc.payment.web.dto.EntitlementResponse;
import com.example.smartdoc.payment.web.dto.PaymentStatusResponse;
import com.example.smartdoc.payment.web.dto.RefundRequest;
import com.example.smartdoc.payment.web.dto.RefundResponse;
import com.example.smartdoc.payment.web.dto.WebhookResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class PaymentService {
    private final PaymentRepository repository;
    private final PaymentProperties properties;
    private final Clock clock;
    private final StripeSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository repository, PaymentProperties properties, Clock clock, StripeSignatureVerifier signatureVerifier, ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
    }

    public CheckoutSessionResponse createCheckoutSession(CheckoutSessionCreateRequest request) {
        Plan plan = resolvePlan(request.planId());
        if (!plan.active()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
        }

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return repository.findCheckoutByIdempotency(request.idempotencyKey())
                .map(session -> mapCheckoutResponse(session, plan, getEntitlementInternal(request.userId())))
                .orElseGet(() -> createCheckoutSessionInternal(request, plan));
        }

        return createCheckoutSessionInternal(request, plan);
    }

    public EntitlementResponse getEntitlement(String userId) {
        return mapEntitlementResponse(getEntitlementInternal(userId));
    }

    public PaymentStatusResponse getStatus(String userId) {
        EntitlementResponse entitlement = getEntitlement(userId);
        CheckoutSessionResponse latestCheckout = repository.findLatestCheckoutForUser(userId)
            .map(session -> mapCheckoutResponse(session, resolvePlan(session.planId()), getEntitlementInternal(userId)))
            .orElse(null);
        return new PaymentStatusResponse(userId, entitlement, latestCheckout);
    }

    public WebhookResponse handleStripeWebhook(byte[] payload, String signatureHeader) {
        signatureVerifier.verify(payload, signatureHeader, properties.stripeWebhookSecret(), properties.webhookToleranceSeconds());
        JsonNode event = parseJson(payload);
        String eventId = requiredText(event, "id");
        String eventType = requiredText(event, "type");

        if (repository.hasWebhookEvent(eventId)) {
            return new WebhookResponse(eventId, eventType, false, true, null, null);
        }

        if (!isSupportedEvent(eventType)) {
            recordWebhookEvent(eventId, eventType, event);
            return new WebhookResponse(eventId, eventType, true, false, null, null);
        }

        WebhookTarget target = resolveTarget(event);
        if (isActivationEvent(eventType)) {
            Entitlement entitlement = repository.activateEntitlement(target.userId(), target.planId(), eventType);
            if (target.session() != null) {
                repository.completeCheckoutSession(target.session().sessionId());
            }
            recordWebhookEvent(eventId, eventType, event);
            return new WebhookResponse(eventId, eventType, true, false, entitlement.status(), target.userId());
        }

        Entitlement entitlement = repository.deactivateEntitlement(target.userId(), eventType);
        recordWebhookEvent(eventId, eventType, event);
        return new WebhookResponse(eventId, eventType, true, false, entitlement.status(), target.userId());
    }

    public RefundResponse refund(RefundRequest request, String internalToken) {
        if (!Objects.equals(properties.internalToken(), internalToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal token");
        }
        Entitlement entitlement = repository.deactivateEntitlement(request.userId(), "refund");
        RefundRecord refund = new RefundRecord(randomId("rfnd"), request.userId(), request.reason(), "refunded", Instant.now(clock));
        repository.saveRefund(refund);
        return new RefundResponse(refund.refundId(), request.userId(), refund.status(), !entitlement.active(), request.reason());
    }

    private CheckoutSessionResponse createCheckoutSessionInternal(CheckoutSessionCreateRequest request, Plan plan) {
        String sessionId = randomId("cs");
        String checkoutUrl = buildCheckoutUrl(sessionId, request.successUrl(), request.cancelUrl());
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("user_id", request.userId());
        metadata.put("plan_id", plan.id());
        if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
            metadata.put("customer_email", request.customerEmail());
        }

        CheckoutSession session = new CheckoutSession(
            sessionId,
            request.userId(),
            plan.id(),
            "pending",
            checkoutUrl,
            request.idempotencyKey(),
            metadata,
            Instant.now(clock),
            Instant.now(clock),
            null
        );
        repository.saveCheckoutSession(session);
        return mapCheckoutResponse(session, plan, getEntitlementInternal(request.userId()));
    }

    private CheckoutSessionResponse mapCheckoutResponse(CheckoutSession session, Plan plan, Entitlement entitlement) {
        return new CheckoutSessionResponse(
            session.sessionId(),
            session.checkoutUrl(),
            session.status(),
            session.userId(),
            session.planId(),
            plan.name(),
            plan.priceCents(),
            plan.currency(),
            entitlement.active(),
            "Checkout session ready"
        );
    }

    private Entitlement getEntitlementInternal(String userId) {
        return repository.findEntitlement(userId).orElseGet(() -> repository.upsertEntitlement(new Entitlement(
            userId,
            properties.defaultPlan().id(),
            "inactive",
            false,
            "bootstrap",
            Instant.now(clock),
            null,
            Map.of()
        )));
    }

    private EntitlementResponse mapEntitlementResponse(Entitlement entitlement) {
        return new EntitlementResponse(
            entitlement.userId(),
            entitlement.planId(),
            entitlement.status(),
            entitlement.active(),
            entitlement.updatedAt(),
            entitlement.expiresAt()
        );
    }

    private Plan resolvePlan(String requestedPlanId) {
        String planId = requestedPlanId == null || requestedPlanId.isBlank() ? properties.defaultPlan().id() : requestedPlanId;
        Plan plan = repository.getPlan(planId);
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
        }
        return plan;
    }

    private String buildCheckoutUrl(String sessionId, String successUrl, String cancelUrl) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.checkoutBaseUrl())
            .queryParam("session_id", sessionId);
        if (successUrl != null && !successUrl.isBlank()) {
            builder.queryParam("success_url", successUrl);
        }
        if (cancelUrl != null && !cancelUrl.isBlank()) {
            builder.queryParam("cancel_url", cancelUrl);
        }
        return builder.build().toUriString();
    }

    private JsonNode parseJson(byte[] payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload", ex);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook event " + field + " is required");
        }
        return value.asText();
    }

    private boolean isSupportedEvent(String eventType) {
        return isActivationEvent(eventType) || isDeactivationEvent(eventType) || eventType.equals("invoice.payment_failed") || eventType.equals("charge.refunded");
    }

    private boolean isActivationEvent(String eventType) {
        return eventType.equals("checkout.session.completed") || eventType.equals("invoice.payment_succeeded") || eventType.equals("customer.subscription.updated");
    }

    private boolean isDeactivationEvent(String eventType) {
        return eventType.equals("customer.subscription.deleted") || eventType.equals("invoice.payment_failed") || eventType.equals("charge.refunded");
    }

    private WebhookTarget resolveTarget(JsonNode event) {
        JsonNode object = event.path("data").path("object");
        String sessionId = object.path("id").asText("");
        String userId = object.path("metadata").path("user_id").asText("");
        String planId = object.path("metadata").path("plan_id").asText("");
        CheckoutSession session = sessionId.isBlank() ? null : repository.findCheckoutSession(sessionId).orElse(null);
        if ((userId == null || userId.isBlank()) && session != null) {
            userId = session.userId();
        }
        if ((planId == null || planId.isBlank()) && session != null) {
            planId = session.planId();
        }
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook payload is missing user metadata");
        }
        return new WebhookTarget(userId, planId == null || planId.isBlank() ? properties.defaultPlan().id() : planId, session);
    }

    private void recordWebhookEvent(String eventId, String eventType, JsonNode event) {
        repository.recordWebhookEvent(new WebhookEventRecord(eventId, eventType, true, false, event.toString(), Instant.now(clock)));
    }

    private String randomId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record WebhookTarget(String userId, String planId, CheckoutSession session) {}
}



