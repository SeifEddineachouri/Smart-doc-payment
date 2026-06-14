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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * Confirms a checkout session on the user's return from Stripe. Stripe's
     * checkout.session.completed webhook is the source of truth, but it may be
     * delayed or undeliverable in local setups, so here we query Stripe directly
     * for the session and activate the entitlement when payment has completed.
     */
    public EntitlementResponse confirmCheckout(String sessionId, String requestedUserId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session_id is required");
        }

        CheckoutSession localSession = repository.findCheckoutSession(sessionId).orElse(null);
        String userId = firstNonBlank(localSession != null ? localSession.userId() : null, requestedUserId);
        String planId = localSession != null ? localSession.planId() : null;

        if (properties.useStripeCheckout()) {
            StripeSessionView view = fetchStripeSession(sessionId);
            userId = firstNonBlank(userId, view.userId());
            planId = firstNonBlank(planId, view.planId());
            if (!view.paid()) {
                if (userId == null || userId.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to resolve user for checkout session");
                }
                return mapEntitlementResponse(getEntitlementInternal(userId));
            }
        } else if (localSession == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkout session not found");
        }

        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to resolve user for checkout session");
        }
        if (planId == null || planId.isBlank()) {
            planId = properties.defaultPlan().id();
        }

        Entitlement entitlement = repository.activateEntitlement(userId, planId, "checkout_confirm");
        if (localSession != null) {
            repository.completeCheckoutSession(sessionId);
        }
        return mapEntitlementResponse(entitlement);
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
        CheckoutSessionCreationResult checkoutSession = properties.useStripeCheckout()
            ? createStripeCheckoutSession(request, plan)
            : createMockCheckoutSession(request, plan);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("user_id", request.userId());
        metadata.put("plan_id", plan.id());
        if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
            metadata.put("customer_email", request.customerEmail());
        }

        CheckoutSession session = new CheckoutSession(
            checkoutSession.sessionId(),
            request.userId(),
            plan.id(),
            "pending",
            checkoutSession.checkoutUrl(),
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
        Plan defaultPlan = properties.defaultPlan().toDomain();
        Plan plan = findPlanByIdOrAlias(requestedPlanId, defaultPlan);
        if (plan != null) {
            return plan;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
    }

    private Plan findPlanByIdOrAlias(String requestedPlanId, Plan defaultPlan) {
        Plan defaultRepositoryPlan = repository.getPlan(defaultPlan.id());
        if (defaultRepositoryPlan == null) {
            return null;
        }

        if (requestedPlanId == null || requestedPlanId.isBlank()) {
            return defaultRepositoryPlan;
        }

        String trimmedRequestedPlanId = requestedPlanId.trim();
        Plan exactMatch = repository.getPlan(trimmedRequestedPlanId);
        if (exactMatch != null) {
            return exactMatch;
        }

        String normalizedRequestedPlanId = normalizePlanIdentifier(trimmedRequestedPlanId);
        for (Plan candidate : repository.listPlans()) {
            String normalizedCandidateId = normalizePlanIdentifier(candidate.id());
            String normalizedCandidateName = normalizePlanIdentifier(candidate.name());
            if (normalizedRequestedPlanId.equals(normalizedCandidateId)
                || normalizedRequestedPlanId.equals(normalizedCandidateName)
                || normalizedCandidateId.contains(normalizedRequestedPlanId)
                || normalizedCandidateName.contains(normalizedRequestedPlanId)
                || normalizedRequestedPlanId.contains(normalizedCandidateId)
                || normalizedRequestedPlanId.contains(normalizedCandidateName)) {
                return candidate;
            }
        }

        return null;
    }

    private String normalizePlanIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private CheckoutSessionCreationResult createMockCheckoutSession(CheckoutSessionCreateRequest request, Plan plan) {
        String sessionId = randomId("cs");
        String checkoutUrl = buildMockCheckoutUrl(sessionId, request.successUrl(), request.cancelUrl());
        return new CheckoutSessionCreationResult(sessionId, checkoutUrl);
    }

    private CheckoutSessionCreationResult createStripeCheckoutSession(CheckoutSessionCreateRequest request, Plan plan) {
        String secretKey = properties.stripeSecretKey();
        if (secretKey == null || secretKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe checkout is enabled but PAYMENT_STRIPE_SECRET_KEY is missing");
        }

        String successUrl = resolveReturnUrl(request.successUrl(), properties.checkoutReturnUrl());
        String cancelUrl = resolveReturnUrl(request.cancelUrl(), properties.checkoutCancelUrl());
        if (successUrl == null || successUrl.isBlank() || cancelUrl == null || cancelUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe checkout requires success and cancel URLs");
        }

        String responseBody;
        int statusCode;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://api.stripe.com/v1/checkout/sessions").openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + secretKey.trim());
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            String payload = buildStripeCheckoutForm(request, plan, successUrl, cancelUrl);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 200 && statusCode < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (responseStream == null) {
                responseBody = "";
            } else {
                responseBody = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to reach Stripe checkout API", ex);
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe checkout session creation failed: " + responseBody);
        }

        try {
            JsonNode json = objectMapper.readTree(responseBody);
            String sessionId = requiredText(json, "id");
            String checkoutUrl = requiredText(json, "url");
            return new CheckoutSessionCreationResult(sessionId, checkoutUrl);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe checkout session response was invalid", ex);
        }
    }

    private String buildStripeCheckoutForm(CheckoutSessionCreateRequest request, Plan plan, String successUrl, String cancelUrl) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("mode", "subscription");
        fields.put("success_url", successUrl);
        fields.put("cancel_url", cancelUrl);
        fields.put("line_items[0][quantity]", "1");
        fields.put("line_items[0][price_data][currency]", plan.currency());
        fields.put("line_items[0][price_data][product_data][name]", plan.name());
        fields.put("line_items[0][price_data][unit_amount]", Integer.toString(plan.priceCents()));
        fields.put("line_items[0][price_data][recurring][interval]", plan.interval());
        fields.put("metadata[user_id]", request.userId());
        fields.put("metadata[plan_id]", plan.id());
        if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
            fields.put("customer_email", request.customerEmail());
        }

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(urlEncode(entry.getKey()));
            body.append('=');
            body.append(urlEncode(entry.getValue()));
        }
        return body.toString();
    }

    private String resolveReturnUrl(String requestValue, String fallbackValue) {
        if (requestValue != null && !requestValue.isBlank()) {
            return requestValue;
        }
        return fallbackValue;
    }

    private String buildMockCheckoutUrl(String sessionId, String successUrl, String cancelUrl) {
        StringBuilder builder = new StringBuilder(properties.checkoutBaseUrl())
            .append("?session_id=")
            .append(urlEncode(sessionId));
        if (successUrl != null && !successUrl.isBlank()) {
            builder.append("&success_url=").append(urlEncode(successUrl));
        }
        if (cancelUrl != null && !cancelUrl.isBlank()) {
            builder.append("&cancel_url=").append(urlEncode(cancelUrl));
        }
        return builder.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private StripeSessionView fetchStripeSession(String sessionId) {
        String secretKey = properties.stripeSecretKey();
        if (secretKey == null || secretKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe checkout is enabled but PAYMENT_STRIPE_SECRET_KEY is missing");
        }

        String responseBody;
        int statusCode;
        try {
            String endpoint = "https://api.stripe.com/v1/checkout/sessions/" + urlEncode(sessionId);
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + secretKey.trim());

            statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 200 && statusCode < 300 ? connection.getInputStream() : connection.getErrorStream();
            responseBody = responseStream == null ? "" : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to reach Stripe checkout API", ex);
        }

        if (statusCode == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkout session not found at Stripe");
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe checkout session retrieval failed: " + responseBody);
        }

        try {
            JsonNode json = objectMapper.readTree(responseBody);
            String paymentStatus = json.path("payment_status").asText("");
            String sessionStatus = json.path("status").asText("");
            boolean paid = "paid".equalsIgnoreCase(paymentStatus)
                || "no_payment_required".equalsIgnoreCase(paymentStatus)
                || "complete".equalsIgnoreCase(sessionStatus);
            String userId = json.path("metadata").path("user_id").asText("");
            String planId = json.path("metadata").path("plan_id").asText("");
            return new StripeSessionView(paid, userId, planId);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe checkout session response was invalid", ex);
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private record CheckoutSessionCreationResult(String sessionId, String checkoutUrl) {}

    private record StripeSessionView(boolean paid, String userId, String planId) {}

    private record WebhookTarget(String userId, String planId, CheckoutSession session) {}

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

}



