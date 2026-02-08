package com.openbooking.inventory.domain.strategy;

import com.openbooking.common.exception.BusinessException;
import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.model.RoomAvailability;
import com.openbooking.inventory.domain.repository.RoomAvailabilityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link DistributedLockReservationStrategy}.
 *
 * These tests focus on the \"Distributed + DB Atomic Update\" behavior:
 * - We do NOT rely on in-memory entity state to decide availability.
 * - We call repository.decreaseAvailabilityAtomically(...) and assert behavior based on rows affected.
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockReservationStrategyTest {

    @Mock
    private RoomAvailabilityRepository repository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @InjectMocks
    private DistributedLockReservationStrategy strategy;

    @Test
    @DisplayName("reserve() should succeed when atomic update affects 1 row")
    void reserve_success_whenAtomicUpdateSucceeds() throws Exception {
        // given
        Long roomId = 101L;
        LocalDate checkIn = LocalDate.of(2026, 1, 1);
        LocalDate checkOut = checkIn.plusDays(1); // single night to simplify
        int quantity = 2;

        ReserveRoomRequest request = new ReserveRoomRequest(
                roomId,
                checkIn,
                checkOut,
                quantity
        );

        // Redisson lock behavior
        given(redissonClient.getLock(anyString())).willReturn(lock);
        given(lock.tryLock(anyLong(), anyLong(), any())).willReturn(true);
        given(lock.isHeldByCurrentThread()).willReturn(true); // so finally block calls unlock()

        // Atomic update succeeds (1 row updated)
        given(repository.decreaseAvailabilityAtomically(eq(roomId), eq(checkIn), eq(quantity)))
                .willReturn(1);

        // Price lookup for total price calculation
        RoomAvailability availability = RoomAvailability.builder()
                .roomId(roomId)
                .availabilityDate(checkIn)
                .availableCount(10)
                .pricePerNight(BigDecimal.valueOf(100))
                .build();

        given(repository.findByRoomIdAndAvailabilityDate(roomId, checkIn))
                .willReturn(Optional.of(availability));

        // when
        ReserveRoomResponse response = strategy.reserve(request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.roomId()).isEqualTo(roomId);
        assertThat(response.quantity()).isEqualTo(quantity);
        // 1 night * 100 * quantity(2) = 200
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(response.status()).isEqualTo("RESERVED");

        verify(repository).decreaseAvailabilityAtomically(eq(roomId), eq(checkIn), eq(quantity));
        verify(repository).findByRoomIdAndAvailabilityDate(eq(roomId), eq(checkIn));
        verify(lock).unlock();
    }

    @Test
    @DisplayName("reserve() should throw BusinessException when atomic update affects 0 rows (not enough availability)")
    void reserve_fail_whenAtomicUpdateReturnsZeroRows() throws Exception {
        // given
        Long roomId = 202L;
        LocalDate checkIn = LocalDate.of(2026, 1, 1);
        LocalDate checkOut = checkIn.plusDays(1);
        int quantity = 5;

        ReserveRoomRequest request = new ReserveRoomRequest(
                roomId,
                checkIn,
                checkOut,
                quantity
        );

        given(redissonClient.getLock(anyString())).willReturn(lock);
        given(lock.tryLock(anyLong(), anyLong(), any())).willReturn(true);
        given(lock.isHeldByCurrentThread()).willReturn(true);

        // Atomic update fails (0 rows updated -> not enough availability)
        given(repository.decreaseAvailabilityAtomically(eq(roomId), eq(checkIn), eq(quantity)))
                .willReturn(0);

        // when / then
        assertThatThrownBy(() -> strategy.reserve(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient availability for room")
                .extracting("errorCode")
                .isEqualTo("INSUFFICIENT_AVAILABILITY");

        verify(repository).decreaseAvailabilityAtomically(eq(roomId), eq(checkIn), eq(quantity));
        verify(lock).unlock();
    }
}

