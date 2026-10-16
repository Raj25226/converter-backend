package com.converter.conversion;

import java.io.*;
import java.nio.file.*;
import org.springframework.web.multipart.MultipartFile;

/** Reopens a staged upload for a background worker without loading it into memory. */
public record StoredMultipartFile(Path path, String originalFilename) implements MultipartFile {
    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return "application/octet-stream";
    }

    @Override
    public boolean isEmpty() {
        return getSize() == 0;
    }

    @Override
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public void transferTo(File destination) throws IOException {
        Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void transferTo(Path destination) throws IOException {
        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
    }
}
