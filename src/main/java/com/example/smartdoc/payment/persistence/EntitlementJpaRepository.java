package com.example.smartdoc.payment.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EntitlementJpaRepository extends JpaRepository<EntitlementEntity, String> {
}
