package com.converter.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tool_stats")
@Getter
@Setter
@NoArgsConstructor
public class ToolStat {

    @Id
    @Column(length = 100)
    private String tool;

    @Column(nullable = false)
    private long count = 0;

    public ToolStat(String tool) {
        this.tool = tool;
        this.count = 0;
    }
}