package com.openbooking.booking.saga;

import com.openbooking.booking.client.InventoryClient;
import com.openbooking.booking.client.PaymentClient;
import com.openbooking.booking.client.dto.ProcessPaymentRequest;
import com.openbooking.booking.client.dto.ProcessPaymentResponse;
import com.openbooking.booking.client.dto.ReserveRoomRequest;
import com.openbooking.booking.client.dto.ReserveRoomResponse;
import com.openbooking.booking.domain.model.Booking;
import com.openbooking.booking.domain.repository.BookingRepository;
import com.openbooking.booking.events.BookingEventPublisher;
import com.openbooking.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookingOrchestrator} (Saga pattern).
 *
 * Verifies:
 * - Happy path: reserve room → payment success → confirm → publish event; idempotency key "booking-{id}" sent to Inventory and Payment
 * - Payment failure: reserve room → payment fail → compensating transaction (releaseRoom) → booking FAILED
 */
@ExtendWith(MockitoExtension.class)
class BookingOrchestratorTest {

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingEventPublisher bookingEventPublisher;

    @InjectMocks
    private BookingOrchestrator orchestrator;

    @Test
    @DisplayName("orchestrateBooking happy path: reserve → pay → confirm → publish event")
    void orchestrateBooking_success() {
        // given
        Booking booking = Booking.builder()
                .id(1L)
                .userId(100L)
                .roomId(101L)
                .checkInDate(LocalDate.of(2026, 2, 1))
                .checkOutDate(LocalDate.of(2026, 2, 3))
                .quantity(2)
                .status(Booking.BookingStatus.PENDING)
                .build();

        ReserveRoomResponse reserveResponse = new ReserveRoomResponse(
                1L, 101L, booking.getCheckInDate(), booking.getCheckOutDate(),
                2, BigDecimal.valueOf(500), "RESERVED"
        );
        ProcessPaymentResponse paymentResponse = new ProcessPaymentResponse(10L, "SUCCESS", "OK");

        when(inventoryClient.reserveRoom(any(ReserveRoomRequest.class))).thenReturn(reserveResponse);
        when(paymentClient.processPayment(any(ProcessPaymentRequest.class))).thenReturn(paymentResponse);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        Booking result = orchestrator.orchestrateBooking(booking);

        // then
        assertThat(result.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        assertThat(result.getTotalPrice()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(result.getPaymentId()).isEqualTo(10L);

        ArgumentCaptor<ReserveRoomRequest> reserveCaptor = ArgumentCaptor.forClass(ReserveRoomRequest.class);
        ArgumentCaptor<ProcessPaymentRequest> paymentCaptor = ArgumentCaptor.forClass(ProcessPaymentRequest.class);
        verify(inventoryClient).reserveRoom(reserveCaptor.capture());
        verify(paymentClient).processPayment(paymentCaptor.capture());
        assertThat(reserveCaptor.getValue().idempotencyKey()).isEqualTo("booking-1");
        assertThat(paymentCaptor.getValue().idempotencyKey()).isEqualTo("booking-1");
        verify(bookingEventPublisher).publishBookingConfirmed(any(Booking.class));
        verify(inventoryClient, never()).releaseRoom(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("orchestrateBooking when payment fails: should call releaseRoom (compensating transaction)")
    void orchestrateBooking_paymentFails_releasesRoom() {
        // given
        Booking booking = Booking.builder()
                .id(2L)
                .userId(200L)
                .roomId(202L)
                .checkInDate(LocalDate.of(2026, 3, 1))
                .checkOutDate(LocalDate.of(2026, 3, 2))
                .quantity(1)
                .status(Booking.BookingStatus.PENDING)
                .build();

        ReserveRoomResponse reserveResponse = new ReserveRoomResponse(
                2L, 202L, booking.getCheckInDate(), booking.getCheckOutDate(),
                1, BigDecimal.valueOf(150), "RESERVED"
        );
        ProcessPaymentResponse paymentResponse = new ProcessPaymentResponse(
                20L, "FAILED", "Insufficient funds"
        );

        when(inventoryClient.reserveRoom(any(ReserveRoomRequest.class))).thenReturn(reserveResponse);
        when(paymentClient.processPayment(any(ProcessPaymentRequest.class))).thenReturn(paymentResponse);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        // when / then
        assertThatThrownBy(() -> orchestrator.orchestrateBooking(booking))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Payment processing failed");

        // Compensating transaction: releaseRoom must be called (may be called in if-block and again in catch)
        ArgumentCaptor<Long> roomIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> checkInCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> checkOutCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> quantityCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> bookingIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(inventoryClient, atLeast(1)).releaseRoom(
                roomIdCaptor.capture(),
                checkInCaptor.capture(),
                checkOutCaptor.capture(),
                quantityCaptor.capture(),
                bookingIdCaptor.capture()
        );

        assertThat(roomIdCaptor.getAllValues().get(0)).isEqualTo(202L);
        assertThat(checkInCaptor.getAllValues().get(0)).isEqualTo("2026-03-01");
        assertThat(checkOutCaptor.getAllValues().get(0)).isEqualTo("2026-03-02");
        assertThat(quantityCaptor.getAllValues().get(0)).isEqualTo(1);
        assertThat(bookingIdCaptor.getAllValues().get(0)).isEqualTo(2L);

        // Idempotency key still sent for reserve and payment (retry-safe)
        ArgumentCaptor<ReserveRoomRequest> reserveCaptor = ArgumentCaptor.forClass(ReserveRoomRequest.class);
        ArgumentCaptor<ProcessPaymentRequest> paymentCaptor = ArgumentCaptor.forClass(ProcessPaymentRequest.class);
        verify(inventoryClient).reserveRoom(reserveCaptor.capture());
        verify(paymentClient).processPayment(paymentCaptor.capture());
        assertThat(reserveCaptor.getValue().idempotencyKey()).isEqualTo("booking-2");
        assertThat(paymentCaptor.getValue().idempotencyKey()).isEqualTo("booking-2");

        verify(bookingEventPublisher, never()).publishBookingConfirmed(any());
    }
}
