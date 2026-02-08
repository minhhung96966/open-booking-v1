package com.openbooking.payment.domain.repository;

import com.openbooking.payment.domain.model.IdempotencyStore;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyStoreRepository extends JpaRepository<IdempotencyStore, String> {
}
