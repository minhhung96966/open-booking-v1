package com.openbooking.inventory.domain.repository;

import com.openbooking.inventory.InventoryServiceApplication;
import com.openbooking.inventory.domain.model.RoomAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link RoomAvailabilityRepository} focusing on the
 * atomic UPDATE used by the \"distributed\" reservation strategy.
 *
 * We use Testcontainers with a real PostgreSQL instance to:
 * - Run Flyway migrations
 * - Verify the SQL-level atomic behavior of decreaseAvailabilityAtomically(...)
 *
 * Even though this test runs sequentially, the SQL guard
 * (available_count >= :quantity) ensures correctness under high concurrency as well.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = InventoryServiceApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RoomAvailabilityRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("inventory_db")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
        System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
        System.setProperty("spring.datasource.username", POSTGRES.getUsername());
        System.setProperty("spring.datasource.password", POSTGRES.getPassword());
    }

    @Autowired
    private RoomAvailabilityRepository repository;

    @Test
    @DisplayName("decreaseAvailabilityAtomically should never oversell: at most initial stock times succeed")
    void decreaseAvailabilityAtomically_neverOversells() {
        // given
        Long roomId = 999L;
        LocalDate date = LocalDate.of(2026, 1, 1);
        int initialStock = 5;

        RoomAvailability availability = RoomAvailability.builder()
                .roomId(roomId)
                .availabilityDate(date)
                .availableCount(initialStock)
                .pricePerNight(BigDecimal.valueOf(100))
                .build();

        repository.saveAndFlush(availability);

        int successfulUpdates = 0;

        // when: try to reserve 1 room 10 times ( > initialStock )
        for (int i = 0; i < 10; i++) {
            int updatedRows = repository.decreaseAvailabilityAtomically(roomId, date, 1);
            if (updatedRows == 1) {
                successfulUpdates++;
            }
        }

        // then
        RoomAvailability reloaded = repository.findByRoomIdAndAvailabilityDate(roomId, date).orElseThrow();

        // We should never succeed more times than initial stock
        assertThat(successfulUpdates).isEqualTo(initialStock);
        assertThat(reloaded.getAvailableCount()).isZero();
    }
}

