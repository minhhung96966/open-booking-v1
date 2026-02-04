package com.openbooking.catalog.domain.repository;

import com.openbooking.catalog.domain.readmodel.HotelReadModel;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

/**
 * MongoDB repository for Hotel Read Model.
 * Used for fast search and read operations (CQRS read side).
 */
public interface HotelReadModelRepository extends MongoRepository<HotelReadModel, String> {
    
    @Query("{ 'city': ?0, 'country': ?1 }")
    List<HotelReadModel> findByCityAndCountry(String city, String country);
    
    @Query("{ 'country': ?0, 'rating': { $gte: ?1 } }")
    List<HotelReadModel> findByCountryAndMinRating(String country, Double minRating);
    
    List<HotelReadModel> findByNameContainingIgnoreCase(String name);
}
