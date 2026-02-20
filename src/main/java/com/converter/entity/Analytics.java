package com.converter.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "analytics")
@Getter
@Setter
public class Analytics {

    @Id
    private Long id = 1L;

    @Column(name = "total_visits")
    private long totalVisits = 0;

    @Column(name = "unique_visitors")
    private long uniqueVisitors = 0;

    @Column(name = "total_conversions")
    private long totalConversions = 0;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}