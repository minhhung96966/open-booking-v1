package com.openbooking.catalog.domain.repository;

import com.openbooking.catalog.domain.model.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
}
