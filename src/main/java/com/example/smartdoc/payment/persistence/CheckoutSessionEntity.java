package com.example.smartdoc.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(
    name = "payment_checkout_sessions",
    indexes = {
        @Index(name = "idx_checkout_user", columnList = "user_id"),
        @Index(name = "idx_checkout_idempotency", columnList = "idempotency_key")
    }
)
public class CheckoutSessionEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "checkout_url", length = 2048)
    private String checkoutUrl;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Convert(converter = StringMapJsonConverter.class)
    @Column(name = "metadata", length = 4096)
    private Map<String, String> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected CheckoutSessionEntity() {
        // JPA
    }

    public CheckoutSessionEntity(
        String sessionId,
        String userId,
        String planId,
        String status,
        String checkoutUrl,
        String idempotencyKey,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.planId = planId;
        this.status = status;
        this.checkoutUrl = checkoutUrl;
        this.idempotencyKey = idempotencyKey;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
