package com.converter.conversion;

import com.converter.exception.AppException;
import com.converter.exception.ConversionException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Drains bounded diagnostics while enforcing a job deadline and output size ceiling. */
@Component
@Slf4j
public class NativeProcessRunner {
    private final ConversionSettings conversionSettings;

    public NativeProcessRunner(ConversionSettings conversionSettings) {
        this.conversionSettings = conversionSettings;
    }

    public String run(
            List<String> command,
            ConversionWorkspace conversionWorkspace,
            Path output,
            long outputLimit)
            throws IOException {
        return execute(
                command,
                conversionWorkspace.directory(),
                conversionWorkspace.remainingMillis(),
                output,
                outputLimit);
    }

    public String execute(
            List<String> command, Path directory, long timeoutMillis, Path output, long outputLimit)
            throws IOException {
        log.debug("Starting native tool {} with timeout {}ms", command.getFirst(), timeoutMillis);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) {
            builder.directory(directory.toFile());
            builder.environment().put("MAGICK_TEMPORARY_PATH", directory.toString());
            builder.environment().put("TMPDIR", directory.toString());
            builder.environment().put("XDG_CACHE_HOME", directory.resolve("cache").toString());
        }
        builder.environment().put("SAL_USE_VCLPLUGIN", conversionSettings.getOfficeVclPlugin());
        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            log.warn("Cannot start conversion executable {}", command.getFirst(), e);
            throw new AppException(
                    "A required conversion tool is unavailable on the server. Please contact the administrator.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        process.getOutputStream().close();
        var diagnostics = new ByteArrayOutputStream(conversionSettings.getNativeDiagnosticBytes());
        Thread drain =
                Thread.ofVirtual()
                        .name("conversion-diagnostics")
                        .start(
                                () -> {
                                    try (InputStream stream = process.getInputStream()) {
                                        byte[] buffer = new byte[4096];
                                        int length;
                                        while ((length = stream.read(buffer)) != -1) {
                                            synchronized (diagnostics) {
                                                int remaining =
                                                        conversionSettings
                                                                        .getNativeDiagnosticBytes()
                                                                - diagnostics.size();
                                                if (remaining > 0)
                                                    diagnostics.write(
                                                            buffer, 0, Math.min(length, remaining));
                                            }
                                        }
                                    } catch (IOException ignored) {
                                        /* Destroying a timed-out process closes its pipe. */
                                    }
                                });
        var children = new HashSet<ProcessHandle>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            children.addAll(process.descendants().toList());
            while (!process.waitFor(
                    conversionSettings.getNativePollIntervalMs(), TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline)
                    throw new AppException(
                            "Conversion exceeded the processing time limit. Try a smaller file.",
                            HttpStatus.GATEWAY_TIMEOUT);
                children.addAll(process.descendants().toList());
                checkOutput(output, outputLimit);
            }
            if (System.nanoTime() >= deadline)
                throw new AppException(
                        "Conversion exceeded the processing time limit. Try a smaller file.",
                        HttpStatus.GATEWAY_TIMEOUT);
            checkOutput(output, outputLimit);
            drain.join(conversionSettings.getNativeDrainTimeoutMs());
            String text;
            synchronized (diagnostics) {
                text = diagnostics.toString(StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0) {
                log.warn(
                        "Conversion executable {} exited {}: {}",
                        command.getFirst(),
                        process.exitValue(),
                        text);
                throw new ConversionException(
                        "The file could not be converted. It may be corrupt, password-protected, or use an unsupported codec.");
            }
            log.debug("Native tool {} completed successfully", command.getFirst());
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(
                    "Conversion was interrupted. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            for (var child : children) {
                try {
                    if (child.isAlive()) child.destroyForcibly();
                } catch (RuntimeException e) {
                    log.warn("Cannot terminate a conversion subprocess", e);
                }
            }
            if (process.isAlive()) {
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (RuntimeException e) {
                    log.warn(
                            "Cannot enumerate conversion subprocesses; terminating root process",
                            e);
                }
                process.destroyForcibly();
                try {
                    process.waitFor(
                            conversionSettings.getNativeTerminationTimeoutSeconds(),
                            TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
        }
    }

    private void checkOutput(Path output, long limit) throws IOException {
        if (output != null && Files.exists(output) && Files.size(output) > limit) {
            throw new ConversionException(
                    "Converted output exceeds the server's output size limit. Try a smaller file.");
        }
    }

    public boolean available(String binary, String versionFlag) {
        try {
            execute(
                    List.of(binary, versionFlag),
                    null,
                    conversionSettings.getNativeProbeTimeoutMs(),
                    null,
                    Long.MAX_VALUE);
            return true;
        } catch (Exception e) {
            log.debug("Native tool probe failed for {}", binary, e);
            return false;
        }
    }

    public static String officeBinary(ConversionSettings conversionSettings) {
        String configured = conversionSettings.getOfficeBinary();
        return configured;
    }
}
