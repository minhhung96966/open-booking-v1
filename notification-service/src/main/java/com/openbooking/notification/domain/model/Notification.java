package com.openbooking.notification.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Notification entity - MongoDB document.
 * Stores notification history for auditing and tracking.
 */
@Document(collection = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {
    @Id
    private String id;

    private Long userId;
    private Long bookingId;
    private NotificationType type;
    private NotificationStatus status;
    private String recipient;
    private String subject;
    private String body;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;

    public enum NotificationType {
        EMAIL,
        SMS,
        PUSH
    }

    public enum NotificationStatus {
        PENDING,
        SENT,
        FAILED
    }
}
