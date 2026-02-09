package com.converter.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class FileService {

    @Value("${file.upload-dir}")
    private String uploadDir;

    public File getFile(String fileId) {
        // Example: fileId = "abc123.pdf"
        File file = new File(uploadDir, fileId);

        // 🔒 Prevent path traversal attacks
        try {
            String canonicalPath = file.getCanonicalPath();
            String canonicalBase = new File(uploadDir).getCanonicalPath();

            if (!canonicalPath.startsWith(canonicalBase)) {
                throw new SecurityException("Invalid file path");
            }
        } catch (Exception e) {
            return null;
        }

        return file;
    }
}