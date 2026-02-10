package com.converter.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
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

    public File convertWordToPdf(MultipartFile file) throws IOException {
        // Note: Word to PDF conversion typically requires external libraries like LibreOffice or commercial APIs
        // This is a placeholder that copies the file. For production, integrate with:
        // - Apache POI + iText (limited functionality)
        // - Documents4j (requires LibreOffice)
        // - Commercial API (Aspose, etc.)

        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".pdf");

        // Placeholder implementation
        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File convertImageFormat(MultipartFile file, String targetFormat) throws IOException {
        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + "." + targetFormat);

        BufferedImage image = ImageIO.read(file.getInputStream());

        // Handle transparency for PNG to JPG conversion
        if ("jpg".equalsIgnoreCase(targetFormat) || "jpeg".equalsIgnoreCase(targetFormat)) {
            BufferedImage rgbImage = new BufferedImage(
                    image.getWidth(),
                    image.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );
            rgbImage.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
            image = rgbImage;
        }

        ImageIO.write(image, targetFormat, outputFile);

        return outputFile;
    }

    public File convertExcelToPdf(MultipartFile file) throws IOException {
        // Note: Excel to PDF requires external libraries or LibreOffice
        // This is a placeholder. For production use:
        // - Documents4j with LibreOffice
        // - Commercial APIs (Aspose, GroupDocs, etc.)

        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".pdf");

        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File convertPdfToExcel(MultipartFile file) throws IOException {
        // Note: PDF to Excel requires OCR or table detection libraries
        // This is a placeholder. For production use:
        // - Tabula (table extraction)
        // - Apache PDFBox + custom table detection
        // - Commercial APIs

        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".xlsx");

        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File convertVideoToAudio(MultipartFile file) throws IOException {
        // Note: Video to audio requires FFmpeg
        // This is a placeholder. For production:
        // - Install FFmpeg on the server
        // - Use ProcessBuilder to execute FFmpeg commands
        // - Or use libraries like Xuggler, Humble Video, or JAVE

        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".mp3");

        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File convertPptToPdf(MultipartFile file) throws IOException {
        // Note: PowerPoint to PDF requires external libraries
        // This is a placeholder. For production use:
        // - Documents4j with LibreOffice
        // - Apache POI + custom rendering (limited)
        // - Commercial APIs

        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".pdf");

        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File convertHeicToJpg(MultipartFile file) throws IOException {
        // Note: HEIC conversion requires special libraries
        // This is a placeholder. For production use:
        // - imageio-heif library
        // - External conversion service
        // - Command-line tools like ImageMagick

        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".jpg");

        Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return outputFile;
    }

    public File convertWebpToPng(MultipartFile file) throws IOException {
        // WebP is supported by ImageIO with appropriate plugins
        String fileId = UUID.randomUUID().toString();
        File outputFile = new File(fileStorageLocation.toFile(), fileId + ".png");

        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                // Fallback if WebP reader not available
                Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                ImageIO.write(image, "png", outputFile);
            }
        } catch (Exception e) {
            // Fallback
            Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return outputFile;
    }

    public File storeFile(MultipartFile file) throws IOException {
        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path targetLocation = fileStorageLocation.resolve(fileName);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        return targetLocation.toFile();
    }
}