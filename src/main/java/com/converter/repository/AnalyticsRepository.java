package com.converter.repository;

import com.converter.entity.Analytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AnalyticsRepository extends JpaRepository<Analytics, Long> {


    @Modifying
    @Transactional
    @Query("UPDATE Analytics a SET a.totalVisits = a.totalVisits + 1, a.updatedAt = instant WHERE a.id = 1")
    void incrementTotalVisits();

    @Modifying
    @Transactional
    @Query("UPDATE Analytics a SET a.uniqueVisitors = a.uniqueVisitors + 1, a.updatedAt = instant WHERE a.id = 1")
    void incrementUniqueVisitors();

    @Modifying
    @Transactional
    @Query("UPDATE Analytics a SET a.totalConversions = a.totalConversions + 1, a.updatedAt = instant WHERE a.id = 1")
    void incrementTotalConversions();
}