package com.openbooking.catalog.domain.readmodel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Room Type Read Model - embedded in Hotel Read Model.
 * Used for search and filtering operations.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomTypeReadModel {
    private Long roomTypeId;
    private String name;
    private String description;
    private Integer maxOccupancy;
    private BigDecimal basePrice;
}
