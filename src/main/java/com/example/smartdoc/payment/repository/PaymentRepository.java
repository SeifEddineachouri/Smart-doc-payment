package com.example.smartdoc.payment.repository;

import com.example.smartdoc.payment.model.CheckoutSession;
import com.example.smartdoc.payment.model.Entitlement;
import com.example.smartdoc.payment.model.Plan;
import com.example.smartdoc.payment.model.RefundRecord;
import com.example.smartdoc.payment.model.WebhookEventRecord;
import java.util.Optional;

public interface PaymentRepository {
    Plan getPlan(String planId);

    CheckoutSession saveCheckoutSession(CheckoutSession session);

    Optional<CheckoutSession> findCheckoutByIdempotency(String idempotencyKey);

    Optional<CheckoutSession> findCheckoutSession(String sessionId);

    Optional<CheckoutSession> findLatestCheckoutForUser(String userId);

    Entitlement upsertEntitlement(Entitlement entitlement);

    Optional<Entitlement> findEntitlement(String userId);

    Entitlement activateEntitlement(String userId, String planId, String source);

    Entitlement deactivateEntitlement(String userId, String source);

    CheckoutSession completeCheckoutSession(String sessionId);

    boolean hasWebhookEvent(String eventId);

    WebhookEventRecord recordWebhookEvent(WebhookEventRecord event);

    RefundRecord saveRefund(RefundRecord refund);
}

