package com.converter.feature;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties("features")
public class FeatureFlagSettings {
    public enum Source {
        DATABASE,
        ENVIRONMENT
    }

    @NotNull private Source source = Source.DATABASE;
    private Map<String, Boolean> defaults = new LinkedHashMap<>();

    @PostConstruct
    void validateKeys() {
        defaults.forEach(
                (key, value) -> {
                    FeatureFlag.fromKey(key);
                    if (value == null)
                        throw new IllegalArgumentException("Feature defaults must be boolean.");
                });
    }
}
