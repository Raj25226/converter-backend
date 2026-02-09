package com.converter.dto;

public class ConversionResponse {
    private String fileId;
    private String fileName;
    private String downloadUrl;
    private long fileSize;
    private String status;
    private String message;

    public ConversionResponse() {}

    public ConversionResponse(String fileId, String fileName, String downloadUrl, long fileSize) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.downloadUrl = downloadUrl;
        this.fileSize = fileSize;
        this.status = "success";
    }

    // Getters and Setters
    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}