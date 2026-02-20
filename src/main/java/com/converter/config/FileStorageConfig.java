package com.converter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class FileStorageConfig {

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${file.output-dir}")
    private String outputDir;

    @Bean(name = "uploadPath")
    public Path uploadPath() throws IOException {
        Path path = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    @Bean(name = "outputPath")
    public Path outputPath() throws IOException {
        Path path = Paths.get(outputDir).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }
}