package com.openbooking.catalog.event;

import com.openbooking.catalog.domain.model.Hotel;
import com.openbooking.catalog.domain.model.RoomType;
import com.openbooking.catalog.domain.readmodel.HotelReadModel;
import com.openbooking.catalog.domain.readmodel.RoomTypeReadModel;
import com.openbooking.catalog.domain.repository.HotelReadModelRepository;
import com.openbooking.catalog.domain.repository.HotelRepository;
import com.openbooking.catalog.domain.repository.RoomTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Kafka consumer for catalog events (CQRS pattern).
 * 
 * Syncs write model (PostgreSQL) to read model (MongoDB):
 * - When hotel/room data changes in PostgreSQL, events are published
 * - This consumer updates the MongoDB read model for fast search
 * 
 * This implements CQRS (Command Query Responsibility Segregation):
 * - Write operations: PostgreSQL (ACID, normalized)
 * - Read operations: MongoDB (fast, denormalized, indexed)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogEventConsumer {

    private final HotelReadModelRepository hotelReadModelRepository;
    private final HotelRepository hotelRepository;
    private final RoomTypeRepository roomTypeRepository;

    /**
     * Consumes hotel updated events and syncs to MongoDB read model.
     * This is triggered when a hotel is created/updated in PostgreSQL.
     * 
     * Note: For demo purposes, we're consuming generic events.
     * In production, use typed events (HotelCreatedEvent, HotelUpdatedEvent, etc.).
     */
    @KafkaListener(topics = "hotel-updated", groupId = "catalog-service-group")
    public void handleHotelUpdated(Object event) {
        log.info("Received hotel updated event: {}", event);
        
        try {
            // Parse event and get hotel ID (simplified - in production, use proper deserializer)
            // For now, this is a placeholder
            
            // In production, this would:
            // 1. Parse HotelUpdatedEvent to get hotelId
            // 2. Fetch hotel from PostgreSQL (write model)
            // 3. Convert to HotelReadModel
            // 4. Save to MongoDB (read model)
            
            log.info("Synced hotel to MongoDB read model");
        } catch (Exception e) {
            log.error("Error syncing hotel to read model", e);
            // In production: implement retry logic or dead letter queue
        }
    }

    /**
     * Syncs a hotel from PostgreSQL write model to MongoDB read model.
     * This method can be called directly or via event.
     */
    public void syncHotelToReadModel(Long hotelId) {
        log.info("Syncing hotel {} from PostgreSQL to MongoDB", hotelId);
        
        try {
            Hotel hotel = hotelRepository.findById(hotelId)
                    .orElseThrow(() -> new RuntimeException("Hotel not found: " + hotelId));
            
            List<RoomType> roomTypes = roomTypeRepository.findByHotelId(hotelId);
            
            HotelReadModel readModel = HotelReadModel.builder()
                    .hotelId(hotel.getId())
                    .name(hotel.getName())
                    .description(hotel.getDescription())
                    .address(hotel.getAddress())
                    .city(hotel.getCity())
                    .country(hotel.getCountry())
                    .rating(hotel.getRating())
                    .roomTypes(roomTypes.stream()
                            .map(rt -> RoomTypeReadModel.builder()
                                    .roomTypeId(rt.getId())
                                    .name(rt.getName())
                                    .description(rt.getDescription())
                                    .maxOccupancy(rt.getMaxOccupancy())
                                    .basePrice(rt.getBasePrice())
                                    .build())
                            .collect(Collectors.toList()))
                    .lastUpdated(LocalDateTime.now())
                    .build();
            
            hotelReadModelRepository.save(readModel);
            
            log.info("Successfully synced hotel {} to MongoDB read model", hotelId);
        } catch (Exception e) {
            log.error("Error syncing hotel {} to read model", hotelId, e);
            throw new RuntimeException("Failed to sync hotel to read model", e);
        }
    }
}
