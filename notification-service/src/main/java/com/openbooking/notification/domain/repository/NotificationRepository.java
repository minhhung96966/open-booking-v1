package com.openbooking.notification.domain.repository;

import com.openbooking.notification.domain.model.Notification;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface NotificationRepository extends MongoRepository<Notification, String> {
    List<Notification> findByUserId(Long userId);
    List<Notification> findByBookingId(Long bookingId);
}
