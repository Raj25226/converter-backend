package com.converter;

import com.converter.exception.ConversionException;
import com.converter.conversion.*;
import com.converter.service.FileConversionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class ConversionFidelityTest {
    @TempDir Path directory;
    private FileConversionService service() { return TestSettings.service(directory); }
    private MockMultipartFile upload(String name, byte[] bytes) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }
    private void text(PDPageContentStream stream, PDFont font, float size, Color color, float x, float y, String text) throws Exception {
        stream.setNonStrokingColor(color); stream.beginText(); stream.setFont(font, size);
        stream.newLineAtOffset(x, y); stream.showText(text); stream.endText();
    }
    private byte[] formattedPdf() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var portrait = new PDPage(new PDRectangle(600, 800)); pdf.addPage(portrait);
            var font = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC);
            try (var stream = new PDPageContentStream(pdf, portrait)) {
                String centered = "Centered heading";
                float width = font.getStringWidth(centered) * 20 / 1000;
                text(stream, font, 20, Color.RED, (600 - width) / 2, 740, centered);
                var body = new PDType1Font(Standard14Fonts.FontName.COURIER);
                String right = "Right aligned";
                text(stream, body, 14, Color.BLUE, 550 - body.getStringWidth(right) * 14 / 1000, 700, right);
                text(stream, body, 12, Color.BLACK, 60, 650, "00123  $1,234.50");
                stream.setStrokingColor(Color.RED); stream.moveTo(60, 640); stream.lineTo(250, 640); stream.stroke();
                var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < 10; x++) for (int y = 0; y < 10; y++) image.setRGB(x, y, Color.GREEN.getRGB());
                stream.drawImage(LosslessFactory.createFromImage(pdf, image), 300, 400, 30, 30);
            }
            var landscape = new PDPage(new PDRectangle(800, 600)); pdf.addPage(landscape);
            try (var stream = new PDPageContentStream(pdf, landscape)) {
                text(stream, font, 18, Color.MAGENTA, 80, 520, "Landscape page");
            }
            pdf.addPage(new PDPage(new PDRectangle(600, 800))); // A blank page is still part of the original.
            pdf.save(bytes); return bytes.toByteArray();
        }
    }
    @Test void pdfWordPreservesColorsPositionsImagesMixedPageSizesAndBlankPages() throws Exception {
        var result = service().pdfToWord(upload("layout.pdf", formattedPdf()));
        var content = DocxContent.read(result.path());
        var runs = DocxContent.runs(content);
        assertTrue(runs.stream().anyMatch(r -> DocxContent.text(r).equals("Centered heading")
                && DocxContent.attribute(r, "color", "val").equalsIgnoreCase("FF0000")));
        assertTrue(runs.stream().anyMatch(r -> DocxContent.text(r).equals("Right aligned")
                && DocxContent.attribute(r, "color", "val").equalsIgnoreCase("0000FF")));
        var rendered = service().wordToPdf(upload("layout.docx", Files.readAllBytes(result.path())));
        try (var original = Loader.loadPDF(formattedPdf()); var pdf = Loader.loadPDF(rendered.path().toFile())) {
            assertEquals(3, pdf.getNumberOfPages());
            for (int page = 0; page < 3; page++) {
                assertEquals(original.getPage(page).getCropBox().getWidth(), pdf.getPage(page).getCropBox().getWidth(), 1);
                assertEquals(original.getPage(page).getCropBox().getHeight(), pdf.getPage(page).getCropBox().getHeight(), 1);
            }
            for (String word : List.of("Centered heading", "Right aligned", "00123")) {
                assertArrayEquals(position(original, word), position(pdf, word), 2, word);
            }
            var image = new PDFRenderer(pdf).renderImage(0);
            var pixel = new Color(image.getRGB(315, 385));
            assertTrue(pixel.getGreen() > 200 && pixel.getRed() < 40 && pixel.getBlue() < 40, pixel.toString());
        }
        assertFalse(result.warnings().isEmpty());
    }
    private float[] position(PDDocument pdf, String expected) throws Exception {
        float[] point = {Float.NaN, Float.NaN};
        var stripper = new PDFTextStripper() {
            @Override protected void writeString(String value, List<TextPosition> glyphs) {
                if (value.contains(expected.split(" ")[0])) { point[0] = glyphs.getFirst().getXDirAdj(); point[1] = glyphs.getFirst().getYDirAdj(); }
            }
        };
        stripper.setStartPage(1); stripper.setEndPage(1); stripper.getText(pdf);
        assertFalse(Float.isNaN(point[0]), expected); return point;
    }
    @Test void pdfExcelKeepsTextTypesAndRichTextColor() throws Exception {
        var result = service().pdfToExcel(upload("styled.pdf", formattedPdf()));
        try (var book = new XSSFWorkbook(Files.newInputStream(result.path()))) {
            var cells = new ArrayList<org.apache.poi.xssf.usermodel.XSSFCell>();
            for (var sheet : book) for (var row : sheet) for (var cell : row) cells.add((org.apache.poi.xssf.usermodel.XSSFCell)cell);
            var heading = cells.stream().filter(c -> c.toString().contains("Centered heading")).findFirst().orElseThrow();
            var font = heading.getRichStringCellValue().getFontAtIndex(0);
            assertTrue(font.getBold()); assertTrue(font.getItalic()); assertEquals(20, font.getFontHeightInPoints());
            assertArrayEquals(new byte[]{(byte)255, 0, 0}, font.getXSSFColor().getRGB());
            assertTrue(cells.stream().anyMatch(c -> c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && c.toString().contains("00123")));
            assertTrue(cells.stream().anyMatch(c -> c.toString().contains("$1,234.50")));
        }
    }
    @Test void scannedPdfKeepsImageAndReportsLackOfEditableText() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.drawImage(LosslessFactory.createFromImage(pdf, new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)), 40, 40, 200, 200);
            }
            pdf.save(bytes); var result = service().pdfToWord(upload("scan.pdf", bytes.toByteArray()));
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("OCR")));
            try (var zip = new ZipFile(result.path().toFile())) {
                assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("word/media/")));
            }
        }
    }
    @Test void wordPdfKeepsHeadersFootersColoredTextAndTables() throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header original");
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer original");
            var paragraph = doc.createParagraph(); paragraph.setAlignment(ParagraphAlignment.CENTER);
            var run = paragraph.createRun(); run.setText("Styled title"); run.setColor("FF0000"); run.setBold(true); run.setFontSize(22);
            var table = doc.createTable(2, 2); table.getRow(0).getCell(0).setText("Table heading");
            table.getRow(1).getCell(0).setText("00123"); table.getRow(1).getCell(1).setText("$42.50");
            doc.write(bytes); var result = service().wordToPdf(upload("original.docx", bytes.toByteArray()));
            try (var pdf = Loader.loadPDF(result.path().toFile())) {
                String text = new PDFTextStripper().getText(pdf);
                for (String expected : List.of("Header original", "Footer original", "Styled title", "Table heading", "00123", "$42.50")) assertTrue(text.contains(expected), text);
            }
        }
    }
    @Test void spreadsheetPdfKeepsPrintAreaLandscapeChartsAndImages() throws Exception {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Original");
            sheet.createRow(0).createCell(0).setCellValue("Visible invoice");
            for (int index = 1; index <= 3; index++) {
                var row = sheet.createRow(index); row.createCell(0).setCellValue("Item " + index); row.createCell(1).setCellValue(index * 10);
            }
            sheet.createRow(50).createCell(0).setCellValue("Outside print area");
            book.setPrintArea(0, 0, 9, 0, 25); sheet.setFitToPage(true);
            sheet.getPrintSetup().setLandscape(true); sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short)1); sheet.getPrintSetup().setFitHeight((short)1);
            var drawing = sheet.createDrawingPatriarch();
            var anchor = new org.apache.poi.xssf.usermodel.XSSFClientAnchor(); anchor.setCol1(0); anchor.setCol2(8); anchor.setRow1(6); anchor.setRow2(21);
            var chart = drawing.createChart(anchor); chart.setTitleText("Chart original"); chart.setTitleOverlay(false);
            var category = chart.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM);
            var values = chart.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT);
            var data = chart.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, category, values);
            data.addSeries(org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(sheet, new org.apache.poi.ss.util.CellRangeAddress(1, 3, 0, 0)),
                    org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(sheet, new org.apache.poi.ss.util.CellRangeAddress(1, 3, 1, 1)));
            chart.plot(data);
            var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 10; x++) for (int y = 0; y < 10; y++) image.setRGB(x, y, Color.GREEN.getRGB());
            var png = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", png);
            var pictureAnchor = new org.apache.poi.xssf.usermodel.XSSFClientAnchor(); pictureAnchor.setCol1(7); pictureAnchor.setCol2(9); pictureAnchor.setRow1(0); pictureAnchor.setRow2(5);
            drawing.createPicture(pictureAnchor, book.addPicture(png.toByteArray(), org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG));
            book.write(bytes); var result = service().excelToPdf(upload("visual.xlsx", bytes.toByteArray()));
            try (var pdf = Loader.loadPDF(result.path().toFile())) {
                assertEquals(1, pdf.getNumberOfPages()); assertTrue(pdf.getPage(0).getCropBox().getWidth() > pdf.getPage(0).getCropBox().getHeight());
                String text = new PDFTextStripper().getText(pdf); assertTrue(text.contains("Visible invoice"), text); assertTrue(text.contains("Chart original"), text);
                assertFalse(text.contains("Outside print area"), text);
                var rendered = new PDFRenderer(pdf).renderImage(0); int green = 0;
                for (int y = 0; y < rendered.getHeight(); y++) for (int x = 0; x < rendered.getWidth(); x++) {
                    var pixel = new Color(rendered.getRGB(x, y));
                    if (pixel.getGreen() > 200 && pixel.getRed() < 40 && pixel.getBlue() < 40) green++;
                }
                assertTrue(green > 100, "Embedded spreadsheet image must remain visible");
            }
        }
    }
    @Test void presentationPdfKeepsSlideSizeColorAndEmbeddedPictures() throws Exception {
        try (var deck = new org.apache.poi.xslf.usermodel.XMLSlideShow(); var bytes = new ByteArrayOutputStream()) {
            deck.setPageSize(new java.awt.Dimension(600, 400));
            var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 10; x++) for (int y = 0; y < 10; y++) image.setRGB(x, y, Color.GREEN.getRGB());
            var png = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", png);
            var data = deck.addPicture(png.toByteArray(), org.apache.poi.sl.usermodel.PictureData.PictureType.PNG);
            for (int index = 0; index < 2; index++) {
                var slide = deck.createSlide(); var box = slide.createTextBox(); box.setAnchor(new java.awt.geom.Rectangle2D.Double(30, 30, 500, 100));
                box.setText("Original slide " + index); box.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontColor(Color.BLUE);
                var picture = slide.createPicture(data); picture.setAnchor(new java.awt.geom.Rectangle2D.Double(200, 200, 40, 40));
            }
            deck.write(bytes); var result = service().pptToPdf(upload("visual.pptx", bytes.toByteArray()));
            try (var pdf = Loader.loadPDF(result.path().toFile())) {
                assertEquals(2, pdf.getNumberOfPages()); assertEquals(600, pdf.getPage(0).getCropBox().getWidth(), 1); assertEquals(400, pdf.getPage(0).getCropBox().getHeight(), 1);
                String text = new PDFTextStripper().getText(pdf); assertTrue(text.contains("Original slide 0")); assertTrue(text.contains("Original slide 1"));
                var pixel = new Color(new PDFRenderer(pdf).renderImage(0).getRGB(220, 220)); assertTrue(pixel.getGreen() > 200 && pixel.getRed() < 40 && pixel.getBlue() < 40);
            }
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("animations")));
        }
    }
    @Test void archiveKeepsContentsDirectoriesAndModificationTime() throws Exception {
        byte[] source;
        long time = 1_700_000_000_000L;
        try (var bytes = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(bytes)) {
                var folder = new ZipEntry("empty/"); folder.setTime(time); zip.putNextEntry(folder); zip.closeEntry();
                var file = new ZipEntry("folder/note.txt"); file.setTime(time); zip.putNextEntry(file); zip.write("original contents".getBytes()); zip.closeEntry();
            }
            source = bytes.toByteArray();
        }
        var result = service().zipExtract(upload("original.zip", source));
        try (var zip = new ZipFile(result.path().toFile())) {
            assertTrue(zip.getEntry("empty/").isDirectory());
            var entry = zip.getEntry("folder/note.txt"); assertEquals(time, entry.getTime());
            assertEquals("original contents", new String(zip.getInputStream(entry).readAllBytes()));
        }
    }
    @Test void compressionRejectsMissingInputAndReportsUnchangedOutput() throws Exception {
        assertThrows(ConversionException.class, () -> service().fileCompress(null));
        Path source = directory.resolve("tiny.png");
        TestSettings.runner().execute(List.of(TestSettings.settings().getImageMagickBinary(), "-size", "1x1", "xc:white", source.toString()), directory, 10000, null, Long.MAX_VALUE);
        var original = Files.readAllBytes(source);
        var result = service().fileCompress(upload("tiny.png", original));
        assertTrue(Files.size(result.path()) <= original.length);
        if (Arrays.equals(original, Files.readAllBytes(result.path()))) {
            assertEquals(List.of("Compression did not reduce the file size. The original file is returned unchanged."), result.warnings());
        }
    }
    @Test void textIntegrityDetectsDroppedUnicodeAndAcceptsExpandedLigatures() {
        assertTrue(PdfTextIntegrity.containsText("Résumé ﬁ", "Résumé\nfi"));
        assertFalse(PdfTextIntegrity.containsText("Résumé 00123", "Résumé 0123"));
        assertFalse(PdfTextIntegrity.containsText("你好", "你"));
    }
}
