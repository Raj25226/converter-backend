package com.converter.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileConversionService {

    @Autowired
    private Path fileStorageLocation;

    public File convertPdfToWord(MultipartFile file) throws IOException {
        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".docx");

        try (PDDocument document = Loader.loadPDF(file.getBytes());
             XWPFDocument wordDoc = new XWPFDocument()) {

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            for (String para : text.split("\n")) {
                if (!para.trim().isEmpty()) {
                    XWPFParagraph paragraph = wordDoc.createParagraph();
                    XWPFRun run = paragraph.createRun();
                    run.setText(para);
                }
            }

            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                wordDoc.write(out);
            }
        }

        return outputFile;
    }

    public File convertImageFormat(MultipartFile file, String targetFormat) throws IOException {
        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + "." + targetFormat);

        BufferedImage image = ImageIO.read(file.getInputStream());
        ImageIO.write(image, targetFormat, outputFile);

        return outputFile;
    }

    public File convertExcelToPdf(MultipartFile file) throws IOException {
        // Placeholder
        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".pdf");

        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File convertVideoToAudio(MultipartFile file) throws IOException {
        // Placeholder (FFmpeg needed)
        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".mp3");

        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File storeFile(MultipartFile file) throws IOException {
        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path targetLocation = fileStorageLocation.resolve(fileName);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        return targetLocation.toFile();
    }
}
