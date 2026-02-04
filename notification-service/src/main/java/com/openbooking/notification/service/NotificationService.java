package com.openbooking.notification.service;

import com.openbooking.notification.domain.model.Notification;
import com.openbooking.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Service for sending notifications (email, SMS, push).
 * Mock implementation for demonstration purposes.
 * In production, this would integrate with email/SMS providers (SendGrid, Twilio, etc.).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /**
     * Sends email notification asynchronously.
     * Mock implementation - simulates email sending.
     */
    @Async
    public void sendEmail(Notification notification) {
        log.info("Sending email notification. Notification ID: {}", notification.getId());
        
        try {
            // Simulate email sending delay
            Thread.sleep(100);
            
            // Mock email sending - in production, use SendGrid, AWS SES, etc.
            notification.setStatus(Notification.NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            notificationRepository.save(notification);
            
            log.info("Email sent successfully. Notification ID: {}", notification.getId());
        } catch (Exception e) {
            log.error("Error sending email. Notification ID: {}", notification.getId(), e);
            notification.setStatus(Notification.NotificationStatus.FAILED);
            notificationRepository.save(notification);
        }
    }
}
