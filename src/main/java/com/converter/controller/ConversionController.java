package com.converter.controller;

import com.converter.dto.ConversionResponse;
import com.converter.service.FileConversionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@Slf4j
@RequestMapping("/api/convert")
public class ConversionController {

    @Autowired
    private FileConversionService conversionService;

    @Value("${app.backend.download-url}")
    private String downloadURL;

    @PostMapping("/pdf-to-word")
    public ResponseEntity<ConversionResponse> convertPdfToWord(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting PDF to Word: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertPdfToWord(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting PDF to Word", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/word-to-pdf")
    public ResponseEntity<ConversionResponse> convertWordToPdf(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting Word to PDF: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertWordToPdf(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting Word to PDF", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/jpg-to-png")
    public ResponseEntity<ConversionResponse> convertJpgToPng(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting JPG to PNG: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertImageFormat(file, "png");

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting JPG to PNG", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/png-to-jpg")
    public ResponseEntity<ConversionResponse> convertPngToJpg(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting PNG to JPG: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertImageFormat(file, "jpg");

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting PNG to JPG", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/excel-to-pdf")
    public ResponseEntity<ConversionResponse> convertExcelToPdf(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting Excel to PDF: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertExcelToPdf(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting Excel to PDF", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/pdf-to-excel")
    public ResponseEntity<ConversionResponse> convertPdfToExcel(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting PDF to Excel: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertPdfToExcel(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting PDF to Excel", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/mp4-to-mp3")
    public ResponseEntity<ConversionResponse> convertMp4ToMp3(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting MP4 to MP3: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertVideoToAudio(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting MP4 to MP3", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/ppt-to-pdf")
    public ResponseEntity<ConversionResponse> convertPptToPdf(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting PowerPoint to PDF: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertPptToPdf(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting PowerPoint to PDF", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/heic-to-jpg")
    public ResponseEntity<ConversionResponse> convertHeicToJpg(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting HEIC to JPG: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertHeicToJpg(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting HEIC to JPG", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/webp-to-png")
    public ResponseEntity<ConversionResponse> convertWebpToPng(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Converting WebP to PNG: {}", file.getOriginalFilename());
            File convertedFile = conversionService.convertWebpToPng(file);

            ConversionResponse response = new ConversionResponse(
                    convertedFile.getName(),
                    convertedFile.getName(),
                    downloadURL + convertedFile.getName(),
                    convertedFile.length()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error converting WebP to PNG", e);
            ConversionResponse errorResponse = new ConversionResponse();
            errorResponse.setStatus("error");
            errorResponse.setMessage("Conversion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}