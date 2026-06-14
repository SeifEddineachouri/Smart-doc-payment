package com.example.smartdoc.payment.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventJpaRepository extends JpaRepository<WebhookEventEntity, String> {
}
