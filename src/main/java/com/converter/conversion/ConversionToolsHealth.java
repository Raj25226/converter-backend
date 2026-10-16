package com.converter.conversion;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.health.contributor.*;
import org.springframework.stereotype.Component;

@Component("conversionTools")
public class ConversionToolsHealth implements HealthIndicator {
    private final ConversionSettings conversionSettings;
    private final NativeProcessRunner nativeProcessRunner;
    private volatile Health cached;
    private volatile long checked;

    public ConversionToolsHealth(
            ConversionSettings conversionSettings, NativeProcessRunner nativeProcessRunner) {
        this.conversionSettings = conversionSettings;
        this.nativeProcessRunner = nativeProcessRunner;
    }

    @Override
    public Health health() {
        if (!conversionSettings.isNativeHealthEnabled()) return Health.unknown().build();
        if (cached != null
                && System.nanoTime() - checked
                        < TimeUnit.SECONDS.toNanos(
                                conversionSettings.getNativeHealthCacheSeconds())) return cached;
        return check();
    }

    private synchronized Health check() {
        if (cached != null
                && System.nanoTime() - checked
                        < TimeUnit.SECONDS.toNanos(
                                conversionSettings.getNativeHealthCacheSeconds())) return cached;
        Map<String, Boolean> tools = new LinkedHashMap<>();
        tools.put(
                "office",
                nativeProcessRunner.available(
                        NativeProcessRunner.officeBinary(conversionSettings), "--version"));
        tools.put(
                "images",
                nativeProcessRunner.available(
                        conversionSettings.getImageMagickBinary(), "-version"));
        tools.put(
                "media",
                nativeProcessRunner.available(conversionSettings.getFfmpegBinary(), "-version"));
        tools.put(
                "mediaProbe",
                nativeProcessRunner.available(conversionSettings.getFfprobeBinary(), "-version"));
        cached =
                (tools.values().stream().allMatch(Boolean::booleanValue)
                                ? Health.up()
                                : Health.down())
                        .withDetail("tools", tools)
                        .build();
        checked = System.nanoTime();
        return cached;
    }
}
