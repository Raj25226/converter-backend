package com.converter.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator;

import java.time.Instant;

@Entity
@Table(name = "conversions", indexes = {
        @Index(name = "idx_conversions_user_id", columnList = "user_id"),
        @Index(name = "idx_conversions_session_id", columnList = "session_id"),
        @Index(name = "idx_conversions_file_id", columnList = "file_id"),
        @Index(name = "idx_conversions_expires_at", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Conversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user; // null for guest users

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "converter_type", nullable = false, length = 100)
    private String converterType;

    @Column(name = "original_file_name", length = 500)
    private String originalFileName;

    @Column(name = "converted_file_name", length = 500)
    private String convertedFileName;

    @Column(name = "file_id", length = 255)
    private String fileId;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}