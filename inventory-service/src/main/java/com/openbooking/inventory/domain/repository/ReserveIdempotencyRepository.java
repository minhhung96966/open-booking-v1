package com.openbooking.inventory.domain.repository;

import com.openbooking.inventory.domain.model.ReserveIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReserveIdempotencyRepository extends JpaRepository<ReserveIdempotency, String> {
}
