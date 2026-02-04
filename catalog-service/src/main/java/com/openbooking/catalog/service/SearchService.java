package com.openbooking.catalog.service;

import com.openbooking.catalog.domain.readmodel.HotelReadModel;
import com.openbooking.catalog.domain.repository.HotelReadModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Search Service using MongoDB Read Model (CQRS).
 * 
 * This service uses the read-optimized MongoDB collection for fast search operations.
 * Write operations (create/update hotels) go to PostgreSQL, and are synced to MongoDB via Kafka events.
 * 
 * Benefits:
 * - Fast search queries without impacting write performance
 * - Read model optimized for search (indexes, denormalized data)
 * - Scalable read operations independent of write operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final HotelReadModelRepository hotelReadModelRepository;

    /**
     * Searches hotels by city and country.
     * Uses MongoDB read model for fast search.
     * Results are cached for better performance.
     */
    @Cacheable(value = "hotels", key = "#city + '_' + #country")
    public List<HotelReadModel> searchHotelsByLocation(String city, String country) {
        log.info("Searching hotels in {}, {}", city, country);
        return hotelReadModelRepository.findByCityAndCountry(city, country);
    }

    /**
     * Searches hotels by country with minimum rating.
     */
    @Cacheable(value = "hotels", key = "#country + '_rating_' + #minRating")
    public List<HotelReadModel> searchHotelsByCountryAndRating(String country, Double minRating) {
        log.info("Searching hotels in {} with rating >= {}", country, minRating);
        return hotelReadModelRepository.findByCountryAndMinRating(country, minRating);
    }

    /**
     * Searches hotels by name.
     */
    @Cacheable(value = "hotels", key = "'name_' + #name")
    public List<HotelReadModel> searchHotelsByName(String name) {
        log.info("Searching hotels by name: {}", name);
        return hotelReadModelRepository.findByNameContainingIgnoreCase(name);
    }
}
