package com.converter.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "daily_usage")
public class DailyUsage {
    @Id
    @Column(length = 80)
    private String id;

    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private int attempts;
}
