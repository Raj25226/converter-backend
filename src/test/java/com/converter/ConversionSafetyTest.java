package com.converter;
import com.converter.conversion.*;
import com.converter.exception.ConversionException;
import com.converter.service.FileConversionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversionSafetyTest {
    @TempDir Path directory;
    private MockMultipartFile upload(String name, byte[] bytes) {return new MockMultipartFile("file",name,"application/octet-stream",bytes);}
    @Test void rejectsPasswordProtectedPdfWithoutPublishing() throws Exception {
        try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); var policy=new StandardProtectionPolicy("owner","password",new AccessPermission());policy.setEncryptionKeyLength(128);pdf.protect(policy);pdf.save(bytes);
            assertThrows(ConversionException.class,()->TestSettings.service(directory).pdfToWord(upload("secret.pdf",bytes.toByteArray())));
        }
        try(var files=Files.list(directory)) {assertEquals(0,files.count());}
    }
    @Test void namespacePrefixedExternalRelationshipsAreRejected() throws Exception {
        try(var bytes=new ByteArrayOutputStream()) {
            try(var zip=new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write("<document/>".getBytes());zip.closeEntry();
                zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
                zip.write(("<r:Relationships xmlns:r=\"http://schemas.openxmlformats.org/package/2006/relationships\"><r:Relationship Id=\"x\" TargetMode=\"External\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"http://localhost/private\"/></r:Relationships>").getBytes());zip.closeEntry();
            }
            assertThrows(ConversionException.class,()->TestSettings.service(directory).wordToPdf(upload("external.docx",bytes.toByteArray())));
        }
    }
    @Test void spreadsheetCellBudgetIsCheckedBeforeNativeRendering() throws Exception {
        Path input=directory.resolve("cells.xlsx");
        try(var out=new ZipOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new ZipEntry("xl/workbook.xml"));out.write("<workbook/>".getBytes());out.closeEntry();
            out.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));out.write("<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row><c/><c/></row></sheetData></worksheet>".getBytes());out.closeEntry();
        }
        var settings=TestSettings.settings();settings.setMaxSpreadsheetCells(1);
        try(var workspace=new ConversionWorkspace(directory,10)) {
            assertThrows(ConversionException.class,()->UploadValidation.office(input,"xlsx",settings,workspace));
        }
    }
    @Test void unsafeZipEntriesStillConsumeExpansionBudget() throws Exception {
        try(var bytes=new ByteArrayOutputStream()) {
            try(var zip=new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("../huge"));zip.write(new byte[1024*1024+1]);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("safe"));zip.write(1);zip.closeEntry();
            }
            var settings=TestSettings.settings();settings.setMaxZipEntryMb(1);
            var service=new FileConversionService(directory,settings,TestSettings.runner());
            assertThrows(ConversionException.class,()->service.zipExtract(upload("bomb.zip",bytes.toByteArray())));
        }
        try(var files=Files.list(directory)) {assertEquals(0,files.count());}
    }
    @Test void enforcesOutputLimitDuringNativeExecution() throws Exception {
        var runner=TestSettings.runner();Path output=directory.resolve("large");
        assertThrows(ConversionException.class,()->runner.execute(java.util.List.of("/bin/sh","-c","head -c 200 /dev/zero > large"),directory,5000,output,100));
    }    @Test void zipRejectsCorruptStoredEntryWithoutPublishing() throws Exception {
        byte[] content = "original contents".getBytes(); var crc = new CRC32(); crc.update(content);
        try (var bytes = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(bytes)) {
                var entry = new ZipEntry("note.txt"); entry.setMethod(ZipEntry.STORED); entry.setSize(content.length); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
            }
            byte[] damaged = bytes.toByteArray();
            int offset = 30 + "note.txt".length(); damaged[offset] ^= 1;
            var failure = assertThrows(ConversionException.class, () -> TestSettings.service(directory).zipExtract(upload("damaged.zip", damaged)));
            assertTrue(failure.getMessage().contains("checksum"), failure.getMessage());
        }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @Test void zipRejectsFileDirectoryConflicts() throws Exception {
        try (var bytes = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(bytes)) {
                for (String name : new String[]{"file", "file/child.txt"}) {
                    zip.putNextEntry(new ZipEntry(name)); zip.write(1); zip.closeEntry();
                }
            }
            assertThrows(ConversionException.class, () -> TestSettings.service(directory).zipExtract(upload("conflict.zip", bytes.toByteArray())));
        }
    }
    @Test void pdfGlyphBudgetIsEnforcedBeforeNativeImport() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700); stream.showText("Too much text"); stream.endText();
            }
            pdf.save(bytes); var settings = TestSettings.settings(); settings.setMaxExtractedCharacters(3);
            var runner = org.mockito.Mockito.mock(NativeProcessRunner.class);
            var converter = new FileConversionService(directory, settings, runner);
            assertThrows(ConversionException.class, () -> converter.pdfToWord(upload("large.pdf", bytes.toByteArray())));
            org.mockito.Mockito.verifyNoInteractions(runner);
        }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @Test void signedPdfCompressionDoesNotInvalidateSignatures() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            var signature = new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature();
            pdf.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.PERMS,
                    new org.apache.pdfbox.cos.COSDictionary());
            var field = new org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField(new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(pdf));
            var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(pdf);
            form.getFields().add(field); field.setValue(signature); pdf.getDocumentCatalog().setAcroForm(form);
            pdf.save(bytes);
            assertThrows(ConversionException.class, () -> TestSettings.service(directory).fileCompress(upload("signed.pdf", bytes.toByteArray())));
        }
    }
    @Test void pdfTextVerificationMismatchReturnsUsableDocumentWithWarning() throws Exception {
        byte[] original = pdfWithText("Original selectable text");
        byte[] verification = pdfWithText("Different extracted text");
        byte[] docx;
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("Original selectable text"); word.write(bytes); docx = bytes.toByteArray();
        }
        var runner = org.mockito.Mockito.mock(NativeProcessRunner.class);
        org.mockito.Mockito.when(runner.run(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(ConversionWorkspace.class), org.mockito.ArgumentMatchers.any(Path.class),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            Path output = call.getArgument(2);
            Files.write(output, output.toString().endsWith(".docx") ? docx : verification);
            return "";
        });
        var converter = new FileConversionService(directory, TestSettings.settings(), runner);
        var result = converter.pdfToWord(upload("original.pdf", original));
        assertTrue(Files.isRegularFile(result.path()));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Text verification found differences")));
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument(Files.newInputStream(result.path()))) {
            assertEquals("Original selectable text", word.getParagraphs().getFirst().getText());
        }
    }
    private byte[] pdfWithText(String text) throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700); stream.showText(text); stream.endText();
            }
            pdf.save(bytes); return bytes.toByteArray();
        }
    }

}
