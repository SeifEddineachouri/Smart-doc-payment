package com.example.smartdoc.payment.repository;

import com.example.smartdoc.payment.model.CheckoutSession;
import com.example.smartdoc.payment.model.Entitlement;
import com.example.smartdoc.payment.model.Plan;
import com.example.smartdoc.payment.model.RefundRecord;
import com.example.smartdoc.payment.model.WebhookEventRecord;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPaymentRepository implements PaymentRepository {
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final Map<String, CheckoutSession> checkoutSessions = new ConcurrentHashMap<>();
    private final Map<String, Entitlement> entitlements = new ConcurrentHashMap<>();
    private final Map<String, WebhookEventRecord> webhookEvents = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Map<String, RefundRecord> refunds = new ConcurrentHashMap<>();

    public InMemoryPaymentRepository(Plan defaultPlan) {
        this.plans.put(defaultPlan.id(), defaultPlan);
    }

    @Override
    public Plan getPlan(String planId) {
        return plans.get(planId);
    }

    @Override
    public CheckoutSession saveCheckoutSession(CheckoutSession session) {
        checkoutSessions.put(session.sessionId(), session);
        if (session.idempotencyKey() != null && !session.idempotencyKey().isBlank()) {
            idempotencyIndex.put(session.idempotencyKey(), session.sessionId());
        }
        return session;
    }

    @Override
    public Optional<CheckoutSession> findCheckoutByIdempotency(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String sessionId = idempotencyIndex.get(idempotencyKey);
        return sessionId == null ? Optional.empty() : Optional.ofNullable(checkoutSessions.get(sessionId));
    }

    @Override
    public Optional<CheckoutSession> findCheckoutSession(String sessionId) {
        return Optional.ofNullable(checkoutSessions.get(sessionId));
    }

    @Override
    public Optional<CheckoutSession> findLatestCheckoutForUser(String userId) {
        return checkoutSessions.values().stream()
            .filter(session -> session.userId().equals(userId))
            .max(Comparator.comparing(CheckoutSession::createdAt));
    }

    @Override
    public Entitlement upsertEntitlement(Entitlement entitlement) {
        entitlements.put(entitlement.userId(), entitlement);
        return entitlement;
    }

    @Override
    public Optional<Entitlement> findEntitlement(String userId) {
        return Optional.ofNullable(entitlements.get(userId));
    }

    @Override
    public Entitlement activateEntitlement(String userId, String planId, String source) {
        Entitlement entitlement = new Entitlement(userId, planId, "active", true, source, Instant.now(), null, Map.of());
        entitlements.put(userId, entitlement);
        return entitlement;
    }

    @Override
    public Entitlement deactivateEntitlement(String userId, String source) {
        String planId = Optional.ofNullable(entitlements.get(userId)).map(Entitlement::planId).orElse("inactive");
        Entitlement entitlement = new Entitlement(userId, planId, "inactive", false, source, Instant.now(), null, Map.of());
        entitlements.put(userId, entitlement);
        return entitlement;
    }

    @Override
    public CheckoutSession completeCheckoutSession(String sessionId) {
        CheckoutSession session = checkoutSessions.get(sessionId);
        if (session == null) {
            return null;
        }
        CheckoutSession completed = new CheckoutSession(
            session.sessionId(),
            session.userId(),
            session.planId(),
            "completed",
            session.checkoutUrl(),
            session.idempotencyKey(),
            session.metadata(),
            session.createdAt(),
            Instant.now(),
            Instant.now()
        );
        checkoutSessions.put(sessionId, completed);
        return completed;
    }

    @Override
    public boolean hasWebhookEvent(String eventId) {
        return webhookEvents.containsKey(eventId);
    }

    @Override
    public WebhookEventRecord recordWebhookEvent(WebhookEventRecord event) {
        webhookEvents.put(event.eventId(), event);
        return event;
    }

    @Override
    public RefundRecord saveRefund(RefundRecord refund) {
        refunds.put(refund.refundId(), refund);
        return refund;
    }
}

