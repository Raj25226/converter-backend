package com.converter;

import static org.junit.jupiter.api.Assertions.*;

import com.converter.conversion.*;
import com.converter.exception.*;
import com.converter.service.FileConversionService;
import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

/** Real encoders/decoders: missing runtime dependencies deliberately fail these tests. */
class NativeConversionTest {
    @TempDir Path directory;
    private final NativeProcessRunner nativeProcessRunner = TestSettings.runner();

    private FileConversionService service() {
        return TestSettings.service(directory);
    }

    private String magick() {
        return System.getenv().getOrDefault("IMAGEMAGICK_BINARY", "magick");
    }

    private void run(String... command) throws Exception {
        nativeProcessRunner.execute(List.of(command), directory, 30000, null, Long.MAX_VALUE);
    }

    private MockMultipartFile upload(Path path) throws Exception {
        return new MockMultipartFile(
                "file",
                path.getFileName().toString(),
                "application/octet-stream",
                Files.readAllBytes(path));
    }

    private Path image(String ext) throws Exception {
        Path path = directory.resolve("source." + ext);
        run(magick(), "-size", "80x60", "gradient:red-blue", path.toString());
        return path;
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png", "webp", "heic", "heif"})
    void convertsImageFormats(String ext) throws Exception {
        var file = upload(image(ext));
        var converter = service();
        var result =
                switch (ext) {
                    case "jpg", "jpeg" -> converter.jpgToPng(file);
                    case "png" -> converter.pngToJpg(file);
                    case "webp" -> converter.webpToPng(file);
                    default -> converter.heicToJpg(file);
                };
        var decoded = ImageIO.read(result.path().toFile());
        assertNotNull(decoded);
        assertEquals(80, decoded.getWidth());
        assertEquals(60, decoded.getHeight());
    }

    @Test
    void webpEncoderAndImageCompressionWork() throws Exception {
        var input = image("jpg");
        var result = service().jpgToWebp(upload(input));
        assertEquals(80, ImageIO.read(result.path().toFile()).getWidth());
        for (String ext : List.of("jpg", "png")) {
            var original = image(ext);
            var compressed = service().fileCompress(upload(original));
            assertTrue(Files.size(compressed.path()) <= Files.size(original));
            assertNotNull(ImageIO.read(compressed.path().toFile()));
        }
    }

    private Path video(String extension, boolean audio) throws Exception {
        Path path = directory.resolve("source." + extension);
        var command =
                new ArrayList<>(
                        List.of(
                                "ffmpeg",
                                "-v",
                                "error",
                                "-nostdin",
                                "-y",
                                "-f",
                                "lavfi",
                                "-i",
                                "testsrc2=size=64x96:rate=10"));
        if (audio)
            command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100"));
        command.addAll(
                List.of(
                        "-t",
                        "1",
                        "-threads",
                        "2",
                        "-c:v",
                        extension.equals("webm") ? "libvpx" : "mpeg4"));
        if (audio)
            command.addAll(
                    List.of(
                            "-c:a",
                            extension.equals("webm")
                                    ? "libopus"
                                    : extension.equals("avi") ? "libmp3lame" : "aac"));
        command.add(path.toString());
        nativeProcessRunner.execute(command, directory, 30000, null, Long.MAX_VALUE);
        return path;
    }

    private void verifyMp3(Path output) throws Exception {
        String json =
                nativeProcessRunner.execute(
                        List.of(
                                "ffprobe",
                                "-v",
                                "error",
                                "-show_entries",
                                "stream=codec_name:format=duration",
                                "-of",
                                "json",
                                output.toString()),
                        directory,
                        10000,
                        null,
                        Long.MAX_VALUE);
        assertTrue(json.contains("mp3"), json);
        assertTrue(Files.size(output) > 1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "mov", "avi", "mkv", "webm"})
    void extractsAudioFromEachContainer(String extension) throws Exception {
        verifyMp3(service().mp4ToMp3(upload(video(extension, true))).path());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "mov", "avi", "webm"})
    void convertsEachVideoContainerToAnimatedGif(String extension) throws Exception {
        var result = service().mp4ToGif(upload(video(extension, false)));
        try (var input = ImageIO.createImageInputStream(result.path().toFile())) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                assertEquals(64, reader.getWidth(0));
                assertEquals(96, reader.getHeight(0));
                assertTrue(reader.getNumImages(true) > 1);
            } finally {
                reader.dispose();
            }
        }
    }

    @Test
    void portraitGifFitsBothDimensions() throws Exception {
        var source = directory.resolve("portrait.mp4");
        run(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "color=red:size=240x960:rate=10",
                "-t",
                "1",
                "-c:v",
                "mpeg4",
                source.toString());
        var decoded = ImageIO.read(service().mp4ToGif(upload(source)).path().toFile());
        assertEquals(120, decoded.getWidth());
        assertEquals(480, decoded.getHeight());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wav", "wave"})
    void convertsWaveAudio(String extension) throws Exception {
        var source = directory.resolve("sound." + extension);
        run(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440",
                "-t",
                "1",
                "-f",
                "wav",
                source.toString());
        verifyMp3(service().wavToMp3(upload(source)).path());
    }

    @Test
    void rejectsMissingAudioAndDisguisedMedia() throws Exception {
        assertThrows(
                ConversionException.class, () -> service().mp4ToMp3(upload(video("mp4", false))));
        assertThrows(
                ConversionException.class,
                () ->
                        service()
                                .wavToMp3(
                                        new MockMultipartFile(
                                                "file",
                                                "sound.wav",
                                                "audio/wav",
                                                "RIFFfakeWAVE".getBytes())));
    }

    @Test
    void legacyExcelRetainsFormattedValues() throws Exception {
        Path source = directory.resolve("legacy.xls");
        try (var workbook = new HSSFWorkbook();
                var out = Files.newOutputStream(source)) {
            var sheet = workbook.createSheet("Legacy");
            sheet.createRow(0).createCell(0).setCellValue("Legacy invoice");
            var amount = sheet.createRow(1).createCell(2);
            amount.setCellValue(42.5);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("$0.00"));
            amount.setCellStyle(style);
            workbook.write(out);
        }
        try (var pdf = Loader.loadPDF(service().excelToPdf(upload(source)).path().toFile())) {
            var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Legacy invoice"), text);
            assertTrue(text.contains("$42.50"), text);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ppt", "pptx"})
    void rendersActualPresentationSlides(String extension) throws Exception {
        var source = directory.resolve("slides." + extension);
        if (extension.equals("pptx")) {
            try (var deck = new XMLSlideShow();
                    var out = Files.newOutputStream(source)) {
                var text = deck.createSlide().createTextBox();
                text.setAnchor(new Rectangle2D.Double(30, 30, 500, 100));
                text.setText("Presentation contents");
                deck.write(out);
            }
        } else {
            try (var deck = new HSLFSlideShow();
                    var out = Files.newOutputStream(source)) {
                var text = new HSLFTextBox();
                text.setAnchor(new Rectangle2D.Double(30, 30, 500, 100));
                text.setText("Presentation contents");
                deck.createSlide().addShape(text);
                deck.write(out);
            }
        }
        try (var pdf = Loader.loadPDF(service().pptToPdf(upload(source)).path().toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(pdf).contains("Presentation contents"));
        }
    }

    @Test
    void legacyWordDocumentRendersText() throws Exception {
        var source = directory.resolve("legacy.docx");
        try (var document = new XWPFDocument();
                var out = Files.newOutputStream(source)) {
            document.createParagraph().createRun().setText("Legacy Word invoice");
            document.write(out);
        }
        var settings = TestSettings.settings();
        run(
                NativeProcessRunner.officeBinary(settings),
                "-env:UserInstallation=" + directory.resolve("fixture-profile").toUri(),
                "--headless",
                "--convert-to",
                "doc:MS Word 97",
                "--outdir",
                directory.toString(),
                source.toString());
        try (var pdf =
                Loader.loadPDF(
                        service()
                                .wordToPdf(upload(directory.resolve("legacy.doc")))
                                .path()
                                .toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Legacy Word invoice"));
        }
    }

    @Test
    void spreadsheetRenderingPreservesSheetsDatesAndMergedCells() throws Exception {
        var source = directory.resolve("complex.xlsx");
        try (var book = new XSSFWorkbook();
                var out = Files.newOutputStream(source)) {
            var sheet = book.createSheet("Invoice");
            sheet.createRow(0).createCell(0).setCellValue("Merged invoice heading");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            var date = sheet.createRow(1).createCell(0);
            date.setCellValue(LocalDateTime.of(2026, 10, 14, 0, 0));
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(style);
            sheet.setColumnWidth(0, 24 * 256);
            book.createSheet("Summary").createRow(0).createCell(0).setCellValue("Summary totals");
            book.createSheet("Private")
                    .createRow(0)
                    .createCell(0)
                    .setCellValue("Hidden confidential text");
            book.setSheetHidden(2, true);
            book.write(out);
        }
        try (var pdf = Loader.loadPDF(service().excelToPdf(upload(source)).path().toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertEquals(2, pdf.getNumberOfPages());
            assertTrue(text.contains("Merged invoice heading"), text);
            assertTrue(text.contains("2026-10-14"), text);
            assertTrue(text.contains("Summary totals"), text);
            assertFalse(text.contains("Hidden confidential text"), text);
        }
    }

    @Test
    void audioKeepsSupportedSampleRateChannelsAndTextTags() throws Exception {
        Path source = directory.resolve("tagged.wav");
        run(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "sine=sample_rate=48000",
                "-t",
                "1",
                "-metadata",
                "title=Original title",
                "-metadata",
                "artist=Original artist",
                source.toString());
        var result = service().wavToMp3(upload(source));
        String metadata =
                nativeProcessRunner.execute(
                        List.of(
                                "ffprobe",
                                "-v",
                                "error",
                                "-show_entries",
                                "stream=channels,sample_rate:format_tags=title,artist",
                                "-of",
                                "json",
                                result.path().toString()),
                        directory,
                        10000,
                        null,
                        Long.MAX_VALUE);
        assertTrue(metadata.contains("48000"), metadata);
        assertTrue(metadata.contains("\"channels\": 1"), metadata);
        assertTrue(metadata.contains("Original title"), metadata);
        assertTrue(metadata.contains("Original artist"), metadata);
    }

    @Test
    void surroundAudioDownmixIsExplicit() throws Exception {
        Path source = directory.resolve("surround.wav");
        run(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "anullsrc=r=48000:cl=5.1",
                "-t",
                "1",
                source.toString());
        var result = service().wavToMp3(upload(source));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("downmixed")));
        verifyMp3(result.path());
    }

    @Test
    void animatedWebpDoesNotSilentlyLoseFrames() throws Exception {
        Path source = directory.resolve("animated.webp");
        run(
                magick(),
                "-size",
                "20x20",
                "xc:red",
                "-size",
                "20x20",
                "xc:blue",
                "-delay",
                "10",
                "-loop",
                "0",
                source.toString());
        var failure =
                assertThrows(ConversionException.class, () -> service().webpToPng(upload(source)));
        assertTrue(failure.getMessage().contains("Animated"), failure.getMessage());
    }

    @Test
    void exifOrientationIsAppliedBeforeMetadataIsRemoved() throws Exception {
        byte[] jpeg = Files.readAllBytes(image("jpg"));
        var bytes = new ByteArrayOutputStream();
        bytes.write(jpeg, 0, 2);
        bytes.write(new byte[] {(byte) 255, (byte) 225, 0, 34});
        bytes.write(
                new byte[] {
                    69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0, 1, 0, 18, 1, 3, 0, 1, 0, 0,
                    0, 6, 0, 0, 0, 0, 0, 0, 0
                });
        bytes.write(jpeg, 2, jpeg.length - 2);
        var result =
                service()
                        .jpgToPng(
                                new MockMultipartFile(
                                        "file", "rotated.jpg", "image/jpeg", bytes.toByteArray()));
        var decoded = ImageIO.read(result.path().toFile());
        assertEquals(60, decoded.getWidth());
        assertEquals(80, decoded.getHeight());
    }

    @Test
    void unprofiledCmykJpegKeepsItsDisplayedColor() throws Exception {
        Path source = directory.resolve("cmyk.jpg");
        run(magick(), "-size", "20x20", "xc:red", "-colorspace", "CMYK", source.toString());
        var result = service().jpgToPng(upload(source));
        var pixel = new Color(ImageIO.read(result.path().toFile()).getRGB(10, 10));
        assertTrue(
                pixel.getRed() > 200 && pixel.getGreen() < 50 && pixel.getBlue() < 50,
                pixel.toString());
    }

    @Test
    void gifPreservesAnamorphicDisplayAspectRatio() throws Exception {
        Path source = directory.resolve("anamorphic.mp4");
        run(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=120x60:rate=5",
                "-t",
                "1",
                "-vf",
                "setsar=2",
                "-c:v",
                "mpeg4",
                "-threads",
                "2",
                source.toString());
        var result = service().mp4ToGif(upload(source));
        var decoded = ImageIO.read(result.path().toFile());
        assertEquals(240, decoded.getWidth());
        assertEquals(60, decoded.getHeight());
    }

    @Test
    void animatedPngDoesNotSilentlyLoseFrames() throws Exception {
        Path source = directory.resolve("animated.png");
        run(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=20x20:rate=2",
                "-t",
                "1",
                "-c:v",
                "apng",
                "-threads",
                "2",
                "-f",
                "apng",
                source.toString());
        var failure =
                assertThrows(ConversionException.class, () -> service().pngToJpg(upload(source)));
        assertTrue(failure.getMessage().contains("Animated"), failure.getMessage());
    }

    @Test
    void processDeadlineAndMissingToolHaveCorrectStatus() {
        var timeout =
                assertThrows(
                        AppException.class,
                        () ->
                                nativeProcessRunner.execute(
                                        List.of("/bin/sh", "-c", "sleep 10"),
                                        directory,
                                        150,
                                        null,
                                        100));
        assertEquals(504, timeout.getStatus().value());
        var missing =
                assertThrows(
                        AppException.class,
                        () ->
                                nativeProcessRunner.execute(
                                        List.of("/no/such/converter"), directory, 1000, null, 100));
        assertEquals(503, missing.getStatus().value());
    }

    @Test
    void drainsLargeDiagnosticsWithoutDeadlocking() throws Exception {
        var text =
                nativeProcessRunner.execute(
                        List.of("/bin/sh", "-c", "yes diagnostic | head -c 200000"),
                        directory,
                        5000,
                        null,
                        100);
        assertEquals(16384, text.getBytes(StandardCharsets.UTF_8).length);
    }
}
