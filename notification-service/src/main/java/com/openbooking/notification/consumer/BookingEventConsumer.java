package com.openbooking.notification.consumer;

import com.openbooking.notification.domain.model.Notification;
import com.openbooking.notification.domain.repository.NotificationRepository;
import com.openbooking.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Kafka consumer for booking events.
 * Implements Choreography pattern - consumes events and triggers async notifications.
 * 
 * Listens to:
 * - booking-confirmed: Sends confirmation email/SMS
 * - booking-cancelled: Sends cancellation notification
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;

    /**
     * Consumes booking confirmed events and sends notifications.
     */
    @KafkaListener(topics = "booking-confirmed", groupId = "notification-service-group")
    public void handleBookingConfirmed(Object event) {
        log.info("Received booking confirmed event: {}", event);
        
        try {
            // Parse event (simplified - in production, use proper deserializer)
            // For now, this is a placeholder - actual implementation would parse BookingConfirmedEvent
            
            // Create notification record
            Notification notification = Notification.builder()
                    .type(Notification.NotificationType.EMAIL)
                    .status(Notification.NotificationStatus.PENDING)
                    .subject("Booking Confirmed")
                    .body("Your booking has been confirmed. Thank you for choosing us!")
                    .createdAt(LocalDateTime.now())
                    .build();
            
            notification = notificationRepository.save(notification);
            
            // Send notification asynchronously
            notificationService.sendEmail(notification);
            
            log.info("Processed booking confirmed event. Notification ID: {}", notification.getId());
        } catch (Exception e) {
            log.error("Error processing booking confirmed event", e);
            // In production: implement retry logic or dead letter queue
        }
    }

    /**
     * Consumes booking cancelled events and sends notifications.
     */
    @KafkaListener(topics = "booking-cancelled", groupId = "notification-service-group")
    public void handleBookingCancelled(Object event) {
        log.info("Received booking cancelled event: {}", event);
        
        try {
            // Parse event (simplified)
            Notification notification = Notification.builder()
                    .type(Notification.NotificationType.EMAIL)
                    .status(Notification.NotificationStatus.PENDING)
                    .subject("Booking Cancelled")
                    .body("Your booking has been cancelled. We hope to see you again soon!")
                    .createdAt(LocalDateTime.now())
                    .build();
            
            notification = notificationRepository.save(notification);
            notificationService.sendEmail(notification);
            
            log.info("Processed booking cancelled event. Notification ID: {}", notification.getId());
        } catch (Exception e) {
            log.error("Error processing booking cancelled event", e);
        }
    }
}
