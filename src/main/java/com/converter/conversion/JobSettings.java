package com.converter.conversion;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "jobs")
public class JobSettings {
    private boolean enabled = true;

    @Min(1)
    @Max(1000)
    private int maxPending = 20;

    @Min(1)
    private int retentionMinutes = 60;

    @Min(1)
    private int queueTimeoutSeconds = 900;

    @Min(1)
    private int uploadTimeoutSeconds = 300;
}
