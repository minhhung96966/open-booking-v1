package com.openbooking.booking.events;

import com.openbooking.booking.domain.model.Booking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka event publisher for booking events.
 * Implements Choreography pattern for async event-driven communication.
 * 
 * Events published:
 * - BookingConfirmedEvent: When booking is confirmed
 * - BookingCancelledEvent: When booking is cancelled
 * 
 * These events are consumed by:
 * - notification-service: Sends email/SMS notifications
 * - catalog-service: Updates read model for analytics/search
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final String TOPIC_BOOKING_CONFIRMED = "booking-confirmed";
    private static final String TOPIC_BOOKING_CANCELLED = "booking-cancelled";

    /**
     * Publishes booking confirmed event to Kafka.
     * This triggers async processing in other services (notifications, analytics).
     */
    public void publishBookingConfirmed(Booking booking) {
        BookingConfirmedEvent event = BookingConfirmedEvent.builder()
                .bookingId(booking.getId())
                .userId(booking.getUserId())
                .roomId(booking.getRoomId())
                .checkInDate(booking.getCheckInDate())
                .checkOutDate(booking.getCheckOutDate())
                .totalPrice(booking.getTotalPrice())
                .status(booking.getStatus().name())
                .timestamp(java.time.Instant.now())
                .build();

        publishEvent(TOPIC_BOOKING_CONFIRMED, String.valueOf(booking.getId()), event);
    }

    /**
     * Publishes booking cancelled event to Kafka.
     */
    public void publishBookingCancelled(Booking booking) {
        BookingCancelledEvent event = BookingCancelledEvent.builder()
                .bookingId(booking.getId())
                .userId(booking.getUserId())
                .roomId(booking.getRoomId())
                .reason("User cancelled")
                .timestamp(java.time.Instant.now())
                .build();

        publishEvent(TOPIC_BOOKING_CANCELLED, String.valueOf(booking.getId()), event);
    }

    /**
     * Publishes event to Kafka with async handling.
     * Uses CompletableFuture for non-blocking operation.
     * 
     * TODO (Java 21 Migration): Can use Virtual Threads for better resource utilization:
     * - Executors.newVirtualThreadPerTaskExecutor() instead of CompletableFuture
     * - Simpler async model without manual thread pool management
     */
    private void publishEvent(String topic, String key, Object event) {
        log.info("Publishing event to topic {}: {}", topic, event);
        
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, event);
        
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Event published successfully to topic {}: offset={}", 
                        topic, result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish event to topic {}", topic, ex);
                // In production: implement retry logic or dead letter queue
            }
        });
    }
}
