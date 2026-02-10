package com.converter.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "analytics")
public class Analytics {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String eventType; // 'visit' or 'conversion'

    @Column
    private String converterType;

    @Column
    private String ipAddress;

    @Column(columnDefinition = "TEXT")
    private String userAgent;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    // Constructors
    public Analytics() {
        this.timestamp = LocalDateTime.now();
    }

    public Analytics(String eventType, String converterType, String ipAddress, String userAgent) {
        this();
        this.eventType = eventType;
        this.converterType = converterType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getConverterType() {
        return converterType;
    }

    public void setConverterType(String converterType) {
        this.converterType = converterType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}