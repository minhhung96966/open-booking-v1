package com.openbooking.catalog.domain.readmodel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Hotel Read Model - MongoDB document for fast search operations.
 * This is the read-optimized version of Hotel entity.
 * Synced from PostgreSQL write model via Kafka events (CQRS pattern).
 */
@Document(collection = "hotels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotelReadModel {
    @Id
    private String id;

    @Indexed
    private Long hotelId; // Reference to PostgreSQL write model

    private String name;
    private String description;
    private String address;
    private String city;
    
    @Indexed
    private String country;

    @Indexed
    private Double rating;

    private List<RoomTypeReadModel> roomTypes;
    private LocalDateTime lastUpdated;
}
