package com.converter;

import com.converter.service.FileConversionService;
import com.converter.exception.ConversionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class FileConversionServiceTest {
    @TempDir Path directory;
    private FileConversionService service() { return new FileConversionService(directory); }
    private MockMultipartFile file(String name, String mime, byte[] bytes) { return new MockMultipartFile("file", name, mime, bytes); }
    private byte[] pdf() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700); stream.showText("Name  Amount"); stream.endText();
            }
            pdf.save(bytes); return bytes.toByteArray();
        }
    }
    @Test void pdfToWordProducesReadableDocument() throws Exception {
        var result = service().pdfToWord(file("a.pdf", "application/pdf", pdf()));
        try (var doc = new XWPFDocument(Files.newInputStream(result.path()))) {
            assertTrue(doc.getParagraphs().stream().anyMatch(p -> p.getText().contains("Amount")));
        }
    }
    @Test void pdfToExcelProducesWorkbook() throws Exception {
        var result = service().pdfToExcel(file("a.pdf", "application/pdf", pdf()));
        try (var book = new XSSFWorkbook(Files.newInputStream(result.path()))) { assertTrue(book.getNumberOfSheets() > 0); }
    }
    @Test void wordToPdfProducesReadablePdf() throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Hello conversion"); doc.write(bytes);
            var result = service().wordToPdf(file("a.docx", "application/octet-stream", bytes.toByteArray()));
            try (var pdf = Loader.loadPDF(result.path().toFile())) { assertEquals(1, pdf.getNumberOfPages()); }
        }
    }
    @Test void excelToPdfProducesPdf() throws Exception {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            book.createSheet().createRow(0).createCell(0).setCellValue("Example"); book.write(bytes);
            var result = service().excelToPdf(file("a.xlsx", "application/octet-stream", bytes.toByteArray()));
            try (var pdf = Loader.loadPDF(result.path().toFile())) { assertEquals(1, pdf.getNumberOfPages()); }
        }
    }
    @Test void rejectsFakePdfAndLeavesNoOutput() throws Exception {
        assertThrows(ConversionException.class, () -> service().pdfToWord(file("a.pdf", "application/pdf", "fake".getBytes())));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @Test void transparentPngConvertsToWhiteJpeg() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            var result = service().pngToJpg(file("a.png", "image/png", bytes.toByteArray()));
            var decoded = ImageIO.read(result.path().toFile());
            assertEquals(0xffffff, decoded.getRGB(0, 0) & 0xffffff);
        }
    }
    @Test void genericCompressionRoundTrips() throws Exception {
        byte[] input = "example data".getBytes();
        var result = service().fileCompress(file("a.txt", "text/plain", input));
        try (var gzip = new GZIPInputStream(Files.newInputStream(result.path()))) { assertArrayEquals(input, gzip.readAllBytes()); }
    }
    @Test void zipSkipsUnsafePathsAndPreservesSafeFile() throws Exception {
        try (var bytes = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(bytes)) {
                for (String name : new String[]{"../secret", "C:\\secret", "safe.txt"}) {
                    zip.putNextEntry(new ZipEntry(name)); zip.write("data".getBytes()); zip.closeEntry();
                }
            }
            var result = service().zipExtract(file("a.zip", "application/zip", bytes.toByteArray()));
            try (var zip = new ZipFile(result.path().toFile())) { assertEquals(1, zip.size()); assertNotNull(zip.getEntry("safe.txt")); }
        }
    }
}
