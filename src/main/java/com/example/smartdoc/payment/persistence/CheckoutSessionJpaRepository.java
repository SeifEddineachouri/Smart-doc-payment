package com.example.smartdoc.payment.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutSessionJpaRepository extends JpaRepository<CheckoutSessionEntity, String> {

    Optional<CheckoutSessionEntity> findFirstByIdempotencyKey(String idempotencyKey);

    List<CheckoutSessionEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}
