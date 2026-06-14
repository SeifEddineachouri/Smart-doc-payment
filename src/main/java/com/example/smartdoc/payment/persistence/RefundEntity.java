package com.example.smartdoc.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "payment_refunds")
public class RefundEntity {

    @Id
    @Column(name = "refund_id", nullable = false, updatable = false)
    private String refundId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefundEntity() {
        // JPA
    }

    public RefundEntity(String refundId, String userId, String reason, String status, Instant createdAt) {
        this.refundId = refundId;
        this.userId = userId;
        this.reason = reason;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getRefundId() {
        return refundId;
    }

    public String getUserId() {
        return userId;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
