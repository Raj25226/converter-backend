package com.converter.repository;

import com.converter.entity.Analytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalyticsRepository extends JpaRepository<Analytics, String> {

    @Query("SELECT COUNT(a) FROM Analytics a WHERE a.eventType = 'visit'")
    long countVisits();

    @Query("SELECT COUNT(a) FROM Analytics a WHERE a.eventType = 'conversion'")
    long countConversions();

    @Query("SELECT COUNT(DISTINCT a.ipAddress) FROM Analytics a WHERE a.eventType = 'visit'")
    long countUniqueVisitors();
}