package com.example.smartdoc.payment.repository;

import com.example.smartdoc.payment.config.PaymentProperties;
import com.example.smartdoc.payment.model.CheckoutSession;
import com.example.smartdoc.payment.model.Entitlement;
import com.example.smartdoc.payment.model.Plan;
import com.example.smartdoc.payment.model.RefundRecord;
import com.example.smartdoc.payment.model.WebhookEventRecord;
import com.example.smartdoc.payment.persistence.CheckoutSessionEntity;
import com.example.smartdoc.payment.persistence.CheckoutSessionJpaRepository;
import com.example.smartdoc.payment.persistence.EntitlementEntity;
import com.example.smartdoc.payment.persistence.EntitlementJpaRepository;
import com.example.smartdoc.payment.persistence.RefundEntity;
import com.example.smartdoc.payment.persistence.RefundJpaRepository;
import com.example.smartdoc.payment.persistence.WebhookEventEntity;
import com.example.smartdoc.payment.persistence.WebhookEventJpaRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-backed {@link PaymentRepository}. Entitlements, checkout sessions,
 * processed webhook events and refunds are persisted so a confirmed subscription
 * survives a payment-service restart — fixing the bug where a paid user was sent
 * back to the paywall after reconnecting.
 *
 * <p>Plans remain in memory: they are static configuration (see
 * {@link PaymentProperties}), not user data, so there is nothing to persist.
 *
 * <p>Tests run this same repository against an in-memory H2 database (see the
 * {@code test} profile), so the persistence path itself is exercised in CI.
 */
@Repository
@Transactional
public class JpaPaymentRepository implements PaymentRepository {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final EntitlementJpaRepository entitlements;
    private final CheckoutSessionJpaRepository checkoutSessions;
    private final WebhookEventJpaRepository webhookEvents;
    private final RefundJpaRepository refunds;
    private final PaymentProperties properties;

    public JpaPaymentRepository(
        EntitlementJpaRepository entitlements,
        CheckoutSessionJpaRepository checkoutSessions,
        WebhookEventJpaRepository webhookEvents,
        RefundJpaRepository refunds,
        PaymentProperties properties
    ) {
        this.entitlements = entitlements;
        this.checkoutSessions = checkoutSessions;
        this.webhookEvents = webhookEvents;
        this.refunds = refunds;
        this.properties = properties;

        Plan defaultPlan = properties.defaultPlan().toDomain();
        this.plans.put(defaultPlan.id(), defaultPlan);
        List<PaymentProperties.PlanConfig> additionalPlans =
            properties.additionalPlans() == null ? List.of() : properties.additionalPlans();
        for (PaymentProperties.PlanConfig planConfig : additionalPlans) {
            Plan plan = planConfig.toDomain();
            this.plans.put(plan.id(), plan);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Plan getPlan(String planId) {
        return plans.get(planId);
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Plan> listPlans() {
        return List.copyOf(plans.values());
    }

    @Override
    public CheckoutSession saveCheckoutSession(CheckoutSession session) {
        checkoutSessions.save(toEntity(session));
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CheckoutSession> findCheckoutByIdempotency(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return checkoutSessions.findFirstByIdempotencyKey(idempotencyKey).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CheckoutSession> findCheckoutSession(String sessionId) {
        return checkoutSessions.findById(sessionId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CheckoutSession> findLatestCheckoutForUser(String userId) {
        return checkoutSessions.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .findFirst()
            .map(this::toDomain);
    }

    @Override
    public Entitlement upsertEntitlement(Entitlement entitlement) {
        entitlements.save(toEntity(entitlement));
        return entitlement;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Entitlement> findEntitlement(String userId) {
        return entitlements.findById(userId).map(this::toDomain);
    }

    @Override
    public Entitlement activateEntitlement(String userId, String planId, String source) {
        Entitlement entitlement = new Entitlement(userId, planId, "active", true, source, Instant.now(), null, Map.of());
        entitlements.save(toEntity(entitlement));
        return entitlement;
    }

    @Override
    public Entitlement deactivateEntitlement(String userId, String source) {
        String planId = entitlements.findById(userId).map(EntitlementEntity::getPlanId).orElse("inactive");
        Entitlement entitlement = new Entitlement(userId, planId, "inactive", false, source, Instant.now(), null, Map.of());
        entitlements.save(toEntity(entitlement));
        return entitlement;
    }

    @Override
    public CheckoutSession completeCheckoutSession(String sessionId) {
        return checkoutSessions.findById(sessionId).map(entity -> {
            entity.setStatus("completed");
            Instant now = Instant.now();
            entity.setUpdatedAt(now);
            entity.setCompletedAt(now);
            checkoutSessions.save(entity);
            return toDomain(entity);
        }).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasWebhookEvent(String eventId) {
        return webhookEvents.existsById(eventId);
    }

    @Override
    public WebhookEventRecord recordWebhookEvent(WebhookEventRecord event) {
        webhookEvents.save(new WebhookEventEntity(
            event.eventId(),
            event.eventType(),
            event.processed(),
            event.duplicate(),
            event.payloadJson(),
            event.processedAt()
        ));
        return event;
    }

    @Override
    public RefundRecord saveRefund(RefundRecord refund) {
        refunds.save(new RefundEntity(
            refund.refundId(),
            refund.userId(),
            refund.reason(),
            refund.status(),
            refund.createdAt()
        ));
        return refund;
    }

    // --- mapping helpers -------------------------------------------------

    private EntitlementEntity toEntity(Entitlement e) {
        return new EntitlementEntity(
            e.userId(),
            e.planId(),
            e.status(),
            e.active(),
            e.source(),
            e.updatedAt() == null ? Instant.now() : e.updatedAt(),
            e.expiresAt()
        );
    }

    private Entitlement toDomain(EntitlementEntity e) {
        return new Entitlement(
            e.getUserId(),
            e.getPlanId(),
            e.getStatus(),
            e.isActive(),
            e.getSource(),
            e.getUpdatedAt(),
            e.getExpiresAt(),
            Map.of()
        );
    }

    private CheckoutSessionEntity toEntity(CheckoutSession s) {
        return new CheckoutSessionEntity(
            s.sessionId(),
            s.userId(),
            s.planId(),
            s.status(),
            s.checkoutUrl(),
            s.idempotencyKey(),
            s.metadata(),
            s.createdAt(),
            s.updatedAt() == null ? s.createdAt() : s.updatedAt(),
            s.completedAt()
        );
    }

    private CheckoutSession toDomain(CheckoutSessionEntity e) {
        return new CheckoutSession(
            e.getSessionId(),
            e.getUserId(),
            e.getPlanId(),
            e.getStatus(),
            e.getCheckoutUrl(),
            e.getIdempotencyKey(),
            e.getMetadata() == null ? Map.of() : e.getMetadata(),
            e.getCreatedAt(),
            e.getUpdatedAt(),
            e.getCompletedAt()
        );
    }
}
