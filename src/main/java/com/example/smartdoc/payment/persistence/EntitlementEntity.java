package com.example.smartdoc.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persistent subscription entitlement, keyed by userId. This is the record whose
 * absence previously caused a paid user to be sent back to the paywall after a
 * payment-service restart: entitlements lived only in memory. Stored in Postgres
 * now so a confirmed subscription survives reconnects and restarts.
 */
@Entity
@Table(name = "payment_entitlements")
public class EntitlementEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "source")
    private String source;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected EntitlementEntity() {
        // JPA
    }

    public EntitlementEntity(
        String userId,
        String planId,
        String status,
        boolean active,
        String source,
        Instant updatedAt,
        Instant expiresAt
    ) {
        this.userId = userId;
        this.planId = planId;
        this.status = status;
        this.active = active;
        this.source = source;
        this.updatedAt = updatedAt;
        this.expiresAt = expiresAt;
    }

    public String getUserId() {
        return userId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
