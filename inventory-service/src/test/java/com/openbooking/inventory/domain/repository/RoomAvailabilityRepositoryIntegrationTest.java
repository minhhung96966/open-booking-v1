package com.openbooking.inventory.domain.repository;

import com.openbooking.inventory.domain.model.RoomAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * Uses @DataJpaTest so only JPA/Repository layer is loaded (no Redis/Redisson).
 * Testcontainers provides a real PostgreSQL instance.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("com.openbooking.inventory.domain.model")
@Testcontainers
class RoomAvailabilityRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("inventory_db")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create"); // create table (no Flyway in @DataJpaTest)
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

