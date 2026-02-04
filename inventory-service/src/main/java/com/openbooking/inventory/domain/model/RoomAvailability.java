package com.openbooking.inventory.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Room availability entity representing available rooms for a specific date.
 * Uses optimistic locking with @Version to handle concurrent updates.
 */
@Entity
@Table(name = "room_availability", indexes = {
        @Index(name = "idx_room_date", columnList = "room_id,availability_date"),
        @Index(name = "idx_date", columnList = "availability_date")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomAvailability {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "availability_date", nullable = false)
    private LocalDate availabilityDate;

    @Column(name = "available_count", nullable = false)
    private Integer availableCount;

    @Column(name = "price_per_night", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerNight;

    @Version
    @Column(name = "version")
    private Long version; // For optimistic locking

    /**
     * Decreases available count by specified amount.
     * Used with optimistic locking to detect concurrent modifications.
     */
    public void decreaseAvailability(int quantity) {
        if (this.availableCount < quantity) {
            throw new IllegalStateException("Insufficient room availability");
        }
        this.availableCount -= quantity;
    }

    /**
     * Increases available count by specified amount.
     * Used for compensating transactions (release room).
     */
    public void increaseAvailability(int quantity) {
        this.availableCount += quantity;
    }
}
