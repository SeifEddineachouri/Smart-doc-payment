package com.example.smartdoc.payment.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<RefundEntity, String> {
}
