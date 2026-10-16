package com.converter.conversion;

import com.converter.exception.AppException;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
public final class ConversionWorkspace implements AutoCloseable {
    private final Path directory;
    private final long deadline;
    private final List<String> warnings = new ArrayList<>();

    public void warn(String message) {
        if (!warnings.contains(message)) warnings.add(message);
    }

    public void clearWarnings() {
        warnings.clear();
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public ConversionWorkspace(Path root, int seconds) throws IOException {
        directory = Files.createTempDirectory(root, "work-").toAbsolutePath().normalize();
        deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    }

    public Path path(String name) {
        return directory.resolve(name);
    }

    public Path directory() {
        return directory;
    }

    public Path stage(MultipartFile file, String extension) throws IOException {
        Path input = path("input." + extension);
        file.transferTo(input.toFile());
        return input;
    }

    public long remainingMillis() {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0)
            throw new AppException(
                    "Conversion exceeded the processing time limit. Try a smaller file.",
                    HttpStatus.GATEWAY_TIMEOUT);
        return remaining;
    }

    public void checkDeadline() {
        if (Thread.currentThread().isInterrupted())
            throw new AppException("Conversion was cancelled.", HttpStatus.SERVICE_UNAVAILABLE);
        remainingMillis();
    }

    @Override
    public void close() {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove conversion workspace {}", directory, e);
        }
    }
}
