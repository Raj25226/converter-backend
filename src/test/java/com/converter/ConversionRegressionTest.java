package com.converter;

import com.converter.exception.ConversionException;
import com.converter.service.FileConversionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversionRegressionTest {
    @TempDir Path output;
    private FileConversionService service() { return TestSettings.service(output); }
    private MockMultipartFile upload(String name, byte[] bytes) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }
    private byte[] tablePdf() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (var item : new Object[][]{{50f,700f,"Name"},{250f,700f,"Count"},{430f,700f,"Code"},{50f,675f,"Alice"},{250f,675f,"7"},{430f,675f,"00123"},{50f,650f,"Bob"},{430f,650f,"00999"}}) {
                    stream.beginText(); stream.setFont(font,12); stream.newLineAtOffset((float)item[0],(float)item[1]); stream.showText((String)item[2]); stream.endText();
                }
            }
            pdf.save(bytes); return bytes.toByteArray();
        }
    }
    @Test void pdfTablesPreserveColumnsAndLeadingZeros() throws Exception {
        var result = service().pdfToExcel(upload("table.pdf", tablePdf()));
        try (var workbook = new XSSFWorkbook(Files.newInputStream(result.path()))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("Code", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("00123", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("00999", sheet.getRow(2).getCell(2).getStringCellValue());
            assertTrue(sheet.getRow(2).getCell(1) == null || sheet.getRow(2).getCell(1).toString().isEmpty());
        }
    }
    @Test void wordPdfPreservesUnicode() throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Résumé — €25.00 — 你好"); doc.write(bytes);
            var result = service().wordToPdf(upload("unicode.docx", bytes.toByteArray()));
            try (var pdf = Loader.loadPDF(result.path().toFile())) {
                String text = new PDFTextStripper().getText(pdf);
                assertTrue(text.contains("Résumé"), text); assertTrue(text.contains("你好"), text);
            }
        }
    }
    @Test void excelPdfPreservesDisplayedCurrencyAndFormulaResult() throws Exception {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Sales");
            var row = sheet.createRow(0); var price = row.createCell(0); price.setCellValue(1234.5);
            var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("$#,##0.00")); price.setCellStyle(style);
            row.createCell(2).setCellFormula("A1*2"); book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.write(bytes); var result = service().excelToPdf(upload("sales.xlsx", bytes.toByteArray()));
            try (var pdf = Loader.loadPDF(result.path().toFile())) {
                String text = new PDFTextStripper().getText(pdf);
                assertTrue(text.contains("$1,234.50"), text); assertTrue(text.contains("2469") || text.contains("2,469"), text);
            }
        }
    }
    @Test void imageOnlyPdfDoesNotPretendToProduceEditableText() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); pdf.save(bytes);
            assertThrows(ConversionException.class, () -> service().pdfToWord(upload("scan.pdf", bytes.toByteArray())));
            assertThrows(ConversionException.class, () -> service().pdfToExcel(upload("scan.pdf", bytes.toByteArray())));
        }
    }
    @Test void invalidZipDoesNotProduceSuccessfulEmptyArchive() {
        assertThrows(ConversionException.class, () -> service().zipExtract(upload("broken.zip", "not a zip".getBytes())));
    }
}
