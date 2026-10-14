package com.converter.conversion;

import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component("conversionTools")
public class ConversionToolsHealth implements HealthIndicator {
    private final ConversionSettings settings;
    private final NativeProcessRunner runner;
    private volatile Health cached;
    private volatile long checked;
    public ConversionToolsHealth(ConversionSettings settings, NativeProcessRunner runner) { this.settings = settings; this.runner = runner; }
    @Override public Health health() {
        if (!settings.isNativeHealthEnabled()) return Health.unknown().build();
        if (cached != null && System.nanoTime() - checked < java.util.concurrent.TimeUnit.SECONDS.toNanos(settings.getNativeHealthCacheSeconds())) return cached;
        return check();
    }
    private synchronized Health check() {
        if (cached != null && System.nanoTime() - checked < java.util.concurrent.TimeUnit.SECONDS.toNanos(settings.getNativeHealthCacheSeconds())) return cached;
        Map<String, Boolean> tools = new LinkedHashMap<>();
        tools.put("office", runner.available(NativeProcessRunner.officeBinary(settings), "--version"));
        tools.put("images", runner.available(settings.getImageMagickBinary(), "-version"));
        tools.put("media", runner.available(settings.getFfmpegBinary(), "-version"));
        tools.put("mediaProbe", runner.available(settings.getFfprobeBinary(), "-version"));
        cached = (tools.values().stream().allMatch(Boolean::booleanValue) ? Health.up() : Health.down()).withDetail("tools", tools).build(); checked = System.nanoTime();
        return cached;
    }
}
