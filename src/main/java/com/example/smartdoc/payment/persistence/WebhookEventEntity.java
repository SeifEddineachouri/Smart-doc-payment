package com.example.smartdoc.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persisted record of every processed Stripe webhook event. Persistence makes
 * idempotency survive restarts: a duplicate webhook redelivered by Stripe after
 * a restart is still recognised and not re-applied.
 */
@Entity
@Table(name = "payment_webhook_events")
public class WebhookEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "duplicate", nullable = false)
    private boolean duplicate;

    @Lob
    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected WebhookEventEntity() {
        // JPA
    }

    public WebhookEventEntity(
        String eventId,
        String eventType,
        boolean processed,
        boolean duplicate,
        String payloadJson,
        Instant processedAt
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processed = processed;
        this.duplicate = duplicate;
        this.payloadJson = payloadJson;
        this.processedAt = processedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public boolean isProcessed() {
        return processed;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
