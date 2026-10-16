package com.converter.service;

import com.converter.conversion.*;
import com.converter.exception.AppException;
import com.converter.exception.ConversionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Conversion outputs are staged, validated and published only after successful processing. */
@Service
@Slf4j
public class FileConversionService {
    private final Path outputPath;
    private final ConversionSettings conversionSettings;
    private final NativeProcessRunner nativeProcessRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public FileConversionService(
            @Qualifier("outputPath") Path outputPath,
            ConversionSettings conversionSettings,
            NativeProcessRunner nativeProcessRunner) {
        this.outputPath = outputPath.toAbsolutePath().normalize();
        this.conversionSettings = conversionSettings;
        this.nativeProcessRunner = nativeProcessRunner;
        ZipSecureFile.setMinInflateRatio(conversionSettings.getOfficeMinInflateRatio());
        ZipSecureFile.setMaxEntrySize(conversionSettings.getOfficeMaxEntryMb() * 1024L * 1024);
        ZipSecureFile.setMaxTextSize(conversionSettings.getOfficeMaxTextMb() * 1024L * 1024);
    }

    public ConvertedFile pdfToWord(MultipartFile file) throws IOException {
        return convert(
                file,
                "docx",
                (conversionWorkspace, output) -> {
                    UploadValidation.extension(file, "pdf");
                    Path input = conversionWorkspace.stage(file, "pdf");
                    final List<PdfPreflight.Page> pages;
                    try (var pdf = loadPdf(input, conversionWorkspace)) {
                        pages =
                                new PdfPreflight(conversionSettings, conversionWorkspace)
                                        .inspectPages(pdf);
                    }
                    Path exported = conversionWorkspace.path("input.docx");
                    var command = officeCommand(conversionWorkspace);
                    command.addAll(
                            List.of(
                                    "--infilter=writer_pdf_import",
                                    "--convert-to",
                                    "docx:Office Open XML Text",
                                    "--outdir",
                                    conversionWorkspace.directory().toString(),
                                    input.toString()));
                    nativeProcessRunner.run(command, conversionWorkspace, exported, outputLimit());
                    UploadValidation.office(
                            exported, "docx", conversionSettings, conversionWorkspace);
                    Path normalized = conversionWorkspace.path("normalized.docx");
                    PdfDocxLayout.normalize(
                            exported, normalized, conversionSettings, conversionWorkspace);
                    Files.move(normalized, exported, StandardCopyOption.REPLACE_EXISTING);
                    if (conversionSettings.getPdfVerification()
                            == ConversionSettings.PdfVerification.DEEP) {
                        // Round-trip the output: enforce page geometry and report text extraction
                        // differences
                        // as warnings.
                        Path verification = conversionWorkspace.path("verification");
                        Files.createDirectory(verification);
                        var verifyCommand = officeCommand(conversionWorkspace);
                        verifyCommand.addAll(
                                List.of(
                                        "--convert-to",
                                        "pdf:writer_pdf_Export",
                                        "--outdir",
                                        verification.toString(),
                                        exported.toString()));
                        Path rendered = verification.resolve("input.pdf");
                        nativeProcessRunner.run(
                                verifyCommand, conversionWorkspace, rendered, outputLimit());
                        try (var check = loadPdf(rendered, conversionWorkspace)) {
                            if (check.getNumberOfPages() != pages.size())
                                throw new ConversionException(
                                        "The PDF layout could not be preserved without changing its page count.");
                            var results =
                                    new PdfPreflight(conversionSettings, conversionWorkspace)
                                            .inspectPages(check);
                            for (int index = 0; index < pages.size(); index++) {
                                var sourcePage = pages.get(index);
                                var resultPage = results.get(index);
                                if (Math.abs(sourcePage.width() - resultPage.width()) > 1
                                        || Math.abs(sourcePage.height() - resultPage.height())
                                                > 1) {
                                    throw new ConversionException(
                                            "The PDF page dimensions could not be preserved reliably.");
                                }
                                if (!PdfTextIntegrity.containsText(
                                        sourcePage.text(), resultPage.text())) {
                                    conversionWorkspace.warn(
                                            "Text verification found differences after conversion. Review the DOCX for font substitutions or missing text.");
                                }
                            }
                        }
                    }
                    conversionWorkspace.warn(
                            "PDF text is positioned in editable text boxes. Tables, headers, and footers may not become native Word structures; review complex layouts before editing.");
                    if (pages.stream().allMatch(page -> page.text().isBlank()))
                        conversionWorkspace.warn(
                                "This PDF contains no selectable text. Its visual content is preserved, but OCR is required to make scanned text editable.");
                    Files.move(exported, output);
                });
    }

    public ConvertedFile pdfToExcel(MultipartFile file) throws IOException {
        return convert(
                file,
                "xlsx",
                (conversionWorkspace, output) -> {
                    UploadValidation.extension(file, "pdf");
                    Path input = conversionWorkspace.stage(file, "pdf");
                    try (var pdf = loadPdf(input, conversionWorkspace)) {
                        new PdfPreflight(conversionSettings, conversionWorkspace).inspect(pdf);
                        new PdfTableExtractor(conversionSettings)
                                .writeWorkbook(pdf, output, conversionWorkspace);
                    }
                    conversionWorkspace.warn(
                            "PDF columns are inferred from text positions. Values are preserved as text; formulas, merged cells, graphics, and original spreadsheet structure cannot be recovered from a PDF.");
                });
    }

    public ConvertedFile wordToPdf(MultipartFile file) throws IOException {
        return officeToPdf(file, "writer_pdf_Export", "docx", "doc");
    }

    public ConvertedFile excelToPdf(MultipartFile file) throws IOException {
        return officeToPdf(file, "calc_pdf_Export", "xlsx", "xls");
    }

    public ConvertedFile pptToPdf(MultipartFile file) throws IOException {
        return officeToPdf(file, "impress_pdf_Export", "pptx", "ppt");
    }

    private ConvertedFile officeToPdf(MultipartFile file, String filter, String... extensions)
            throws IOException {
        return convert(
                file,
                "pdf",
                (conversionWorkspace, output) -> {
                    String extension = UploadValidation.extension(file, extensions);
                    Path input = conversionWorkspace.stage(file, extension);
                    UploadValidation.office(
                            input, extension, conversionSettings, conversionWorkspace);
                    if (extension.equals("xls"))
                        validateLegacySpreadsheet(input, conversionWorkspace);
                    Path exported =
                            conversionWorkspace.path(
                                    FilenameUtils.getBaseName(input.getFileName().toString())
                                            + ".pdf");
                    var command = officeCommand(conversionWorkspace);
                    command.addAll(
                            List.of(
                                    "--convert-to",
                                    "pdf:" + filter,
                                    "--outdir",
                                    conversionWorkspace.directory().toString(),
                                    input.toString()));
                    nativeProcessRunner.run(command, conversionWorkspace, exported, outputLimit());
                    // LibreOffice can exit zero when an import/export fails, so exit status alone
                    // is
                    // insufficient.
                    if (!Files.isRegularFile(exported) || Files.size(exported) == 0)
                        throw new ConversionException(
                                "The Office document could not be rendered. Check that it is valid and not password-protected.");
                    try (var pdf = loadPdf(exported, conversionWorkspace)) {
                        if (pdf.getNumberOfPages() == 0)
                            throw new ConversionException("The Office document produced no pages.");
                    }
                    Files.move(exported, output);
                    if (Set.of("ppt", "pptx").contains(extension))
                        conversionWorkspace.warn(
                                "PDF preserves static slides; animations, transitions, speaker notes, and embedded audio/video are not retained.");
                });
    }

    private List<String> officeCommand(ConversionWorkspace conversionWorkspace) throws IOException {
        Path profile = conversionWorkspace.path("profile");
        Files.createDirectories(profile.resolve("user"));
        Files.writeString(
                profile.resolve("user/registrymodifications.xcu"),
                """
            <?xml version="1.0" encoding="UTF-8"?>
            <oor:items xmlns:oor="http://openoffice.org/2001/registry">
              <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
                <prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>
              </item>
              <item oor:path="/org.openoffice.Office.Common/Load">
                <prop oor:name="UpdateDocMode" oor:op="fuse"><value>0</value></prop>
              </item>
            </oor:items>
            """);
        return new ArrayList<>(
                List.of(
                        NativeProcessRunner.officeBinary(conversionSettings),
                        "-env:UserInstallation=" + profile.toUri(),
                        "--headless",
                        "--nologo",
                        "--nodefault",
                        "--norestore",
                        "--nofirststartwizard"));
    }

    private void validateLegacySpreadsheet(Path input, ConversionWorkspace conversionWorkspace)
            throws IOException {
        try (var book = WorkbookFactory.create(input.toFile())) {
            long cells = 0;
            for (var sheet : book)
                for (var row : sheet) {
                    conversionWorkspace.checkDeadline();
                    cells += row.getPhysicalNumberOfCells();
                    if (cells > conversionSettings.getMaxSpreadsheetCells())
                        throw new ConversionException(
                                "Spreadsheet contains too many cells. Split it into smaller workbooks.");
                }
        }
    }

    public ConvertedFile jpgToPng(MultipartFile file) throws IOException {
        return image(file, "png", 0, "jpg", "jpeg");
    }

    public ConvertedFile pngToJpg(MultipartFile file) throws IOException {
        return image(file, "jpg", conversionSettings.getJpegQuality(), "png");
    }

    public ConvertedFile jpgToWebp(MultipartFile file) throws IOException {
        return image(file, "webp", conversionSettings.getWebpQuality(), "jpg", "jpeg");
    }

    public ConvertedFile webpToPng(MultipartFile file) throws IOException {
        return image(file, "png", 0, "webp");
    }

    public ConvertedFile heicToJpg(MultipartFile file) throws IOException {
        return image(file, "jpg", conversionSettings.getJpegQuality(), "heic", "heif");
    }

    private ConvertedFile image(
            MultipartFile file, String outputExtension, int quality, String... extensions)
            throws IOException {
        return convert(
                file,
                outputExtension,
                (conversionWorkspace, output) -> {
                    String extension = UploadValidation.extension(file, extensions);
                    Path input = conversionWorkspace.stage(file, extension);
                    validateImage(input, extension, conversionWorkspace);
                    renderImage(
                            input,
                            extension,
                            output,
                            outputExtension,
                            quality,
                            conversionWorkspace);
                });
    }

    private List<String> magickCommand() {
        return new ArrayList<>(
                List.of(
                        conversionSettings.getImageMagickBinary(),
                        "-limit",
                        "memory",
                        conversionSettings.getImageMemoryLimit(),
                        "-limit",
                        "map",
                        conversionSettings.getImageMapLimit(),
                        "-limit",
                        "disk",
                        conversionSettings.getImageDiskLimit(),
                        "-limit",
                        "thread",
                        Integer.toString(conversionSettings.getNativeThreads())));
    }

    private void validateImage(
            Path input, String extension, ConversionWorkspace conversionWorkspace)
            throws IOException {
        StillImageValidation.check(input, extension, conversionWorkspace);
        if (Set.of("jpg", "jpeg", "png", "webp").contains(extension)) {
            UploadValidation.image(
                    input,
                    extension.equals("jpg") || extension.equals("jpeg")
                            ? Set.of("jpeg", "jpg")
                            : Set.of(extension),
                    conversionSettings);
        } else {
            byte[] header;
            try (var stream = Files.newInputStream(input)) {
                header = stream.readNBytes(64);
            }
            String signature = new String(header, StandardCharsets.ISO_8859_1);
            if (header.length < 16
                    || !signature.substring(4, 8).equals("ftyp")
                    || List.of("heic", "heix", "hevc", "hevx", "mif1", "msf1").stream()
                            .noneMatch(signature::contains)) {
                throw new ConversionException("This file is not a valid HEIC/HEIF image.");
            }
        }
        // Check the actual native decoder format and dimensions before allocating an output raster.
        var command = magickCommand();
        command.addAll(
                List.of("-ping", imageSource(input, extension), "-format", "%m %w %h", "info:"));
        String[] info =
                nativeProcessRunner
                        .run(command, conversionWorkspace, null, outputLimit())
                        .strip()
                        .split("\\s+");
        if (info.length != 3)
            throw new ConversionException("The image metadata could not be read.");
        String format = info[0].toLowerCase(Locale.ROOT);
        Set<String> expected =
                switch (extension) {
                    case "jpg", "jpeg" -> Set.of("jpeg");
                    case "heic", "heif" -> Set.of("heic", "heif");
                    default -> Set.of(extension);
                };
        if (!expected.contains(format))
            throw new ConversionException("The image content does not match the selected format.");
        UploadValidation.dimensions(
                Long.parseLong(info[1]), Long.parseLong(info[2]), conversionSettings);
    }

    private String imageSource(Path input, String extension) {
        String decoder =
                switch (extension) {
                    case "jpg", "jpeg" -> "jpeg";
                    case "heic", "heif" -> "heic";
                    default -> extension;
                };
        return decoder + ":" + input + "[0]";
    }

    private void renderImage(
            Path input,
            String inputExtension,
            Path output,
            String outputExtension,
            int quality,
            ConversionWorkspace conversionWorkspace)
            throws IOException {
        conversionWorkspace.warn(
                "Image metadata is removed; orientation is applied and colors are converted to sRGB.");
        if (outputExtension.equals("jpg") || outputExtension.equals("webp"))
            conversionWorkspace.warn("This conversion uses lossy image encoding.");
        if (outputExtension.equals("jpg") && !Set.of("jpg", "jpeg").contains(inputExtension))
            conversionWorkspace.warn(
                    "JPEG does not support transparency; transparent areas use a white background.");
        if (Set.of("heic", "heif").contains(inputExtension))
            conversionWorkspace.warn(
                    "The primary HEIC/HEIF image is converted; auxiliary images, depth maps, and HDR data are not retained.");
        var command = magickCommand();
        command.addAll(List.of(imageSource(input, inputExtension), "-auto-orient"));
        String profile = conversionSettings.getImageSrgbProfile();
        if (profile == null || profile.isBlank()) {
            profile =
                    List.of(
                                    "/usr/share/color/icc/sRGB.icc",
                                    "/System/Library/ColorSync/Profiles/sRGB Profile.icc")
                            .stream()
                            .filter(path -> Files.isRegularFile(Path.of(path)))
                            .findFirst()
                            .orElse("");
        }
        if (!profile.isBlank()) {
            if (!Files.isRegularFile(Path.of(profile)))
                throw new AppException(
                        "The server color profile is unavailable.", HttpStatus.SERVICE_UNAVAILABLE);
            command.addAll(List.of("-profile", profile));
        } else
            conversionWorkspace.warn(
                    "No sRGB ICC profile is installed; embedded color profiles cannot be transformed accurately.");
        command.addAll(List.of("-colorspace", "sRGB", "-strip"));
        if (outputExtension.equals("jpg"))
            command.addAll(List.of("-background", "white", "-alpha", "remove", "-alpha", "off"));
        if (quality > 0) command.addAll(List.of("-quality", Integer.toString(quality)));
        command.add(output.toString());
        nativeProcessRunner.run(command, conversionWorkspace, output, outputLimit());
        if (!Files.isRegularFile(output))
            throw new ConversionException("The image encoder did not produce an output file.");
        // These outputs are read by the same ImageIO codecs used for API validation.
        UploadValidation.image(
                output,
                switch (outputExtension) {
                    case "jpg" -> Set.of("jpeg", "jpg");
                    default -> Set.of(outputExtension);
                },
                conversionSettings);
    }

    public ConvertedFile mp4ToMp3(MultipartFile file) throws IOException {
        return audio(file, "mp4", "mov", "avi", "mkv", "webm");
    }

    public ConvertedFile wavToMp3(MultipartFile file) throws IOException {
        return audio(file, "wav", "wave");
    }

    private ConvertedFile audio(MultipartFile file, String... extensions) throws IOException {
        return convert(
                file,
                "mp3",
                (conversionWorkspace, output) -> {
                    String extension = UploadValidation.extension(file, extensions);
                    Path input = conversionWorkspace.stage(file, extension);
                    JsonNode metadata = media(input, extension, conversionWorkspace, "a:0");
                    conversionWorkspace.warn(
                            "The first audio track is encoded as lossy MP3 at "
                                    + conversionSettings.getAudioBitrate()
                                    + "; video, other tracks, chapters, and artwork are not retained. Supported text tags are copied.");
                    if (!hasStream(metadata, "audio"))
                        throw new ConversionException("This file does not contain an audio track.");
                    var source = metadata.path("streams").get(0);
                    int channels = source.path("channels").asInt();
                    int sampleRate = source.path("sample_rate").asInt();
                    Set<Integer> mp3Rates =
                            Set.of(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000);
                    int targetRate =
                            mp3Rates.contains(sampleRate)
                                    ? sampleRate
                                    : conversionSettings.getAudioSampleRate();
                    if (targetRate != sampleRate)
                        conversionWorkspace.warn(
                                "The audio sample rate is converted to "
                                        + targetRate
                                        + " Hz for MP3 compatibility.");
                    if (channels > 2)
                        conversionWorkspace.warn(
                                "MP3 supports mono or stereo; surround audio is downmixed to stereo.");
                    var command = ffmpegCommand();
                    command.addAll(
                            List.of(
                                    "-protocol_whitelist",
                                    "file",
                                    "-i",
                                    input.toString(),
                                    "-map",
                                    "0:a:0",
                                    "-vn",
                                    "-map_metadata",
                                    "0",
                                    "-map_chapters",
                                    "-1",
                                    "-c:a",
                                    "libmp3lame",
                                    "-b:a",
                                    conversionSettings.getAudioBitrate(),
                                    "-ar",
                                    Integer.toString(targetRate),
                                    "-ac",
                                    Integer.toString(channels == 1 ? 1 : 2),
                                    "-id3v2_version",
                                    "3",
                                    "-threads",
                                    Integer.toString(conversionSettings.getNativeThreads()),
                                    output.toString()));
                    nativeProcessRunner.run(command, conversionWorkspace, output, outputLimit());
                    JsonNode encoded = probe(output, conversionWorkspace);
                    if (!hasStream(encoded, "audio")
                            || !encoded.path("format").path("format_name").asText().equals("mp3")
                            || !encoded.path("streams")
                                    .get(0)
                                    .path("codec_name")
                                    .asText()
                                    .equals("mp3"))
                        throw new ConversionException("The audio output could not be verified.");
                    var encodedStream = encoded.path("streams").get(0);
                    if (encodedStream.path("sample_rate").asInt() != targetRate
                            || encodedStream.path("channels").asInt() != (channels == 1 ? 1 : 2)) {
                        throw new ConversionException(
                                "The audio output settings could not be verified.");
                    }
                    double duration = source.path("duration").asDouble(Double.NaN);
                    if (!Double.isFinite(duration) && Set.of("wav", "wave").contains(extension))
                        duration = metadata.path("format").path("duration").asDouble(Double.NaN);
                    double encodedDuration =
                            encoded.path("format").path("duration").asDouble(Double.NaN);
                    if (Double.isFinite(duration)
                            && Double.isFinite(encodedDuration)
                            && Math.abs(duration - encodedDuration) > 2) {
                        throw new ConversionException(
                                "The audio output duration differs from the original track.");
                    }
                });
    }

    public ConvertedFile mp4ToGif(MultipartFile file) throws IOException {
        return convert(
                file,
                "gif",
                (conversionWorkspace, output) -> {
                    String extension =
                            UploadValidation.extension(file, "mp4", "mov", "avi", "webm");
                    Path input = conversionWorkspace.stage(file, extension);
                    JsonNode metadata = media(input, extension, conversionWorkspace, "V:0");
                    conversionWorkspace.warn(
                            "GIF has no audio and uses a limited color palette. Output is limited to "
                                    + conversionSettings.getGifDurationSeconds()
                                    + " seconds, "
                                    + conversionSettings.getGifFramesPerSecond()
                                    + " fps, and "
                                    + conversionSettings.getGifMaxDimension()
                                    + " pixels per dimension.");
                    if (!hasStream(metadata, "video"))
                        throw new ConversionException("This file does not contain a video track.");
                    var command = ffmpegCommand();
                    command.addAll(
                            List.of(
                                    "-protocol_whitelist",
                                    "file",
                                    "-t",
                                    Integer.toString(conversionSettings.getGifDurationSeconds()),
                                    "-i",
                                    input.toString(),
                                    "-filter_complex_threads",
                                    Integer.toString(conversionSettings.getFilterThreads()),
                                    "-filter_complex",
                                    "[0:V:0]fps=%d,scale=w='max(1,trunc(iw*sar*min(1,min(%d/(iw*sar),%d/ih))))':h='max(1,trunc(ih*min(1,min(%d/(iw*sar),%d/ih))))':flags=lanczos,setsar=1,split[v][p];[p]palettegen=stats_mode=diff[pal];[v][pal]paletteuse=dither=bayer[out]"
                                            .formatted(
                                                    conversionSettings.getGifFramesPerSecond(),
                                                    conversionSettings.getGifMaxDimension(),
                                                    conversionSettings.getGifMaxDimension(),
                                                    conversionSettings.getGifMaxDimension(),
                                                    conversionSettings.getGifMaxDimension()),
                                    "-map",
                                    "[out]",
                                    "-t",
                                    Integer.toString(conversionSettings.getGifDurationSeconds()),
                                    "-loop",
                                    "0",
                                    "-threads",
                                    Integer.toString(conversionSettings.getNativeThreads()),
                                    output.toString()));
                    nativeProcessRunner.run(command, conversionWorkspace, output, outputLimit());
                    UploadValidation.image(output, Set.of("gif"), conversionSettings);
                });
    }

    private List<String> ffmpegCommand() {
        return new ArrayList<>(
                List.of(
                        conversionSettings.getFfmpegBinary(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-nostdin",
                        "-y",
                        "-max_alloc",
                        Long.toString(conversionSettings.getFfmpegMaxAllocationBytes()),
                        "-threads",
                        Integer.toString(conversionSettings.getNativeThreads()),
                        "-filter_threads",
                        Integer.toString(conversionSettings.getFilterThreads())));
    }

    private JsonNode probe(Path input, ConversionWorkspace conversionWorkspace) throws IOException {
        return probe(input, conversionWorkspace, "a:0");
    }

    private JsonNode probe(Path input, ConversionWorkspace conversionWorkspace, String streamSpec)
            throws IOException {
        var command =
                List.of(
                        conversionSettings.getFfprobeBinary(),
                        "-v",
                        "error",
                        "-max_alloc",
                        Long.toString(conversionSettings.getFfmpegMaxAllocationBytes()),
                        "-select_streams",
                        streamSpec,
                        "-protocol_whitelist",
                        "file",
                        "-show_entries",
                        "format=format_name,duration:stream=codec_name,codec_type,width,height,channels,sample_rate,duration,sample_aspect_ratio",
                        "-of",
                        "json",
                        input.toString());
        return objectMapper.readTree(
                nativeProcessRunner.run(command, conversionWorkspace, null, outputLimit()));
    }

    private JsonNode media(
            Path input,
            String extension,
            ConversionWorkspace conversionWorkspace,
            String streamSpec)
            throws IOException {
        byte[] bytes;
        try (var stream = Files.newInputStream(input)) {
            bytes = stream.readNBytes(16);
        }
        String header = new String(bytes, StandardCharsets.ISO_8859_1);
        boolean valid =
                switch (extension) {
                    case "mp4", "mov" ->
                            header.length() >= 8
                                    && Set.of("ftyp", "moov", "mdat", "wide", "free", "skip")
                                            .contains(header.substring(4, 8));
                    case "wav", "wave" ->
                            header.length() >= 12
                                    && (header.startsWith("RIFF")
                                            || header.startsWith("RF64")
                                            || header.startsWith("RIFX"))
                                    && header.substring(8, 12).equals("WAVE");
                    case "avi" ->
                            header.length() >= 12
                                    && header.startsWith("RIFF")
                                    && header.substring(8, 12).equals("AVI ");
                    default ->
                            bytes.length >= 4
                                    && (bytes[0] & 255) == 0x1a
                                    && (bytes[1] & 255) == 0x45
                                    && (bytes[2] & 255) == 0xdf
                                    && (bytes[3] & 255) == 0xa3;
                };
        if (!valid)
            throw new ConversionException(
                    "The media content does not match the selected file type.");
        JsonNode metadata = probe(input, conversionWorkspace, streamSpec);
        Set<String> formats =
                Set.of(metadata.path("format").path("format_name").asText().split(","));
        boolean expected =
                switch (extension) {
                    case "wav", "wave" -> formats.contains("wav");
                    case "avi" -> formats.contains("avi");
                    case "mkv", "webm" -> formats.contains("matroska") || formats.contains("webm");
                    default -> formats.contains("mov") || formats.contains("mp4");
                };
        if (!expected)
            throw new ConversionException(
                    "The media container does not match the selected file type.");
        for (var stream : metadata.path("streams")) {
            if (stream.path("codec_type").asText().equals("video")) {
                long width = stream.path("width").asLong(), height = stream.path("height").asLong();
                UploadValidation.dimensions(width, height, conversionSettings);
                String[] aspect = stream.path("sample_aspect_ratio").asText("1:1").split(":");
                if (aspect.length == 2) {
                    double numerator = Double.parseDouble(aspect[0]),
                            denominator = Double.parseDouble(aspect[1]);
                    if (numerator > 0 && denominator > 0) {
                        double displayWidth = width * numerator / denominator;
                        if (!Double.isFinite(displayWidth)
                                || displayWidth > conversionSettings.getMaxImageDimension())
                            throw new ConversionException(
                                    "Video display dimensions exceed the server's image size limit.");
                        UploadValidation.dimensions(
                                Math.max(1, Math.round(displayWidth)), height, conversionSettings);
                    }
                }
            }
        }
        return metadata;
    }

    private boolean hasStream(JsonNode metadata, String type) {
        for (var stream : metadata.path("streams"))
            if (stream.path("codec_type").asText().equals(type)) return true;
        return false;
    }

    public ConvertedFile zipExtract(MultipartFile file) throws IOException {
        return convert(
                file,
                "zip",
                (conversionWorkspace, output) -> {
                    UploadValidation.extension(file, "zip");
                    Path input = conversionWorkspace.stage(file, "zip");
                    long total = 0;
                    int entries = 0, safeEntries = 0, skipped = 0;
                    Set<String> names = new HashSet<>();
                    Set<String> files = new HashSet<>();
                    Set<String> directories = new HashSet<>();
                    try (var zip = ZipFile.builder().setPath(input).get();
                            var target = new ZipArchiveOutputStream(output.toFile())) {
                        target.setEncoding("UTF-8");
                        var sourceEntries = zip.getEntries();
                        byte[] buffer = new byte[8192];
                        while (sourceEntries.hasMoreElements()) {
                            conversionWorkspace.checkDeadline();
                            var entry = sourceEntries.nextElement();
                            if (++entries > conversionSettings.getMaxZipEntries())
                                throw new ConversionException("ZIP contains too many entries.");
                            if (!zip.canReadEntryData(entry))
                                throw new ConversionException(
                                        "Encrypted or unsupported ZIP entries cannot be extracted.");
                            String name = entry.getName();
                            int type = entry.getUnixMode() & 0170000;
                            boolean safe =
                                    safeArchiveName(name)
                                            && !entry.isUnixSymlink()
                                            && (type == 0 || type == 0100000 || type == 0040000);
                            if (safe) {
                                String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
                                String path =
                                        entry.isDirectory()
                                                ? normalized.substring(0, normalized.length() - 1)
                                                : normalized;
                                if (!names.add(path)
                                        || files.contains(path)
                                        || (!entry.isDirectory() && directories.contains(path))) {
                                    throw new ConversionException(
                                            "ZIP contains duplicate or conflicting file paths.");
                                }
                                for (int slash = path.indexOf('/');
                                        slash >= 0;
                                        slash = path.indexOf('/', slash + 1)) {
                                    String parent = path.substring(0, slash);
                                    if (files.contains(parent))
                                        throw new ConversionException(
                                                "ZIP contains conflicting file and directory paths.");
                                    directories.add(parent);
                                }
                                if (entry.isDirectory()) directories.add(path);
                                else files.add(path);
                                var copy = new ZipArchiveEntry(name);
                                if (entry.getLastModifiedTime() != null)
                                    copy.setLastModifiedTime(entry.getLastModifiedTime());
                                if (entry.getComment() != null) copy.setComment(entry.getComment());
                                if (entry.getUnixMode() != 0)
                                    copy.setUnixMode(entry.getUnixMode() & ~06000);
                                target.putArchiveEntry(copy);
                                safeEntries++;
                            } else skipped++;
                            long size = 0;
                            var crc = new CRC32();
                            try (var content = zip.getInputStream(entry)) {
                                int length;
                                while ((length = content.read(buffer)) != -1) {
                                    conversionWorkspace.checkDeadline();
                                    size += length;
                                    total += length;
                                    crc.update(buffer, 0, length);
                                    if (size > conversionSettings.getMaxZipEntryMb() * 1024L * 1024
                                            || total
                                                    > conversionSettings.getMaxZipExpandedMb()
                                                            * 1024L
                                                            * 1024) {
                                        throw new ConversionException(
                                                "ZIP contents exceed the safe uncompressed size limit.");
                                    }
                                    if (safe) target.write(buffer, 0, length);
                                }
                            }
                            if (size != entry.getSize() || crc.getValue() != entry.getCrc())
                                throw new ConversionException(
                                        "ZIP contains a damaged entry (size or checksum mismatch).");
                            if (safe) target.closeArchiveEntry();
                        }
                    }
                    if (entries > 0 && safeEntries == 0)
                        throw new ConversionException("The ZIP contains no safe files to extract.");
                    if (skipped > 0)
                        conversionWorkspace.warn(skipped + " unsafe archive entries were omitted.");
                });
    }

    private boolean safeArchiveName(String name) {
        if (name == null
                || name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || name.contains(":")
                || name.codePoints().anyMatch(Character::isISOControl)) return false;
        for (String part : name.split("/"))
            if (part.isEmpty() || part.equals("..") || part.equals(".")) return false;
        return name.length() <= conversionSettings.getArchiveMaxNameLength();
    }

    public ConvertedFile fileCompress(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty())
            throw new ConversionException("Upload a non-empty file to convert.");
        String extension =
                Objects.requireNonNullElse(
                                FilenameUtils.getExtension(file.getOriginalFilename()), "")
                        .toLowerCase(Locale.ROOT);
        if (Set.of("jpg", "jpeg", "png").contains(extension)) {
            String outputExtension = extension.equals("jpeg") ? "jpg" : extension;
            return convert(
                    file,
                    outputExtension,
                    (conversionWorkspace, output) -> {
                        Path input = conversionWorkspace.stage(file, extension);
                        validateImage(input, extension, conversionWorkspace);
                        renderImage(
                                input,
                                extension,
                                output,
                                outputExtension,
                                outputExtension.equals("jpg")
                                        ? conversionSettings.getCompressionJpegQuality()
                                        : 0,
                                conversionWorkspace);
                        keepSmaller(input, output, conversionWorkspace);
                    });
        }
        if (extension.equals("pdf"))
            return convert(
                    file,
                    "pdf",
                    (conversionWorkspace, output) -> {
                        Path input = conversionWorkspace.stage(file, "pdf");
                        try (var pdf = loadPdf(input, conversionWorkspace)) {
                            if (!pdf.getSignatureDictionaries().isEmpty())
                                throw new ConversionException(
                                        "Recompressing a digitally signed PDF would invalidate its signature. Upload an unsigned copy.");
                            pdf.save(output.toFile());
                        }
                        keepSmaller(input, output, conversionWorkspace);
                    });
        return convert(
                file,
                "gz",
                (conversionWorkspace, output) -> {
                    try (var input = file.getInputStream();
                            var stream = new GZIPOutputStream(Files.newOutputStream(output))) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = input.read(buffer)) != -1) {
                            conversionWorkspace.checkDeadline();
                            stream.write(buffer, 0, length);
                        }
                    }
                });
    }

    private void keepSmaller(Path original, Path output, ConversionWorkspace conversionWorkspace)
            throws IOException {
        if (Files.size(output) >= Files.size(original)) {
            Files.copy(original, output, StandardCopyOption.REPLACE_EXISTING);
            conversionWorkspace.clearWarnings();
            conversionWorkspace.warn(
                    "Compression did not reduce the file size. The original file is returned unchanged.");
        }
    }

    private PDDocument loadPdf(Path input, ConversionWorkspace conversionWorkspace)
            throws IOException {
        UploadValidation.pdfHeader(input);
        final PDDocument pdf;
        try {
            pdf =
                    Loader.loadPDF(
                            input.toFile(),
                            () ->
                                    new ScratchFile(
                                            MemoryUsageSetting.setupTempFileOnly()
                                                    .setTempDir(
                                                            conversionWorkspace
                                                                    .directory()
                                                                    .toFile())));
        } catch (InvalidPasswordException e) {
            throw new ConversionException(
                    "Password-protected PDFs are not supported. Upload an unencrypted copy.");
        }
        if (pdf.isEncrypted()) {
            pdf.close();
            throw new ConversionException(
                    "Password-protected PDFs are not supported. Upload an unencrypted copy.");
        }
        if (pdf.getNumberOfPages() == 0) {
            pdf.close();
            throw new ConversionException("This PDF contains no pages.");
        }
        if (pdf.getNumberOfPages() > conversionSettings.getMaxPdfPages()) {
            pdf.close();
            throw new ConversionException(
                    "PDF exceeds the server's page limit. Split it into smaller documents.");
        }
        return pdf;
    }

    private long outputLimit() {
        return conversionSettings.getMaxOutputSizeMb() * 1024L * 1024;
    }

    private ConvertedFile convert(MultipartFile file, String extension, Operation operation)
            throws IOException {
        long started = System.nanoTime();
        log.debug("Starting conversion to {}", extension);
        if (file == null || file.isEmpty())
            throw new ConversionException("Upload a non-empty file to convert.");
        String id = UUID.randomUUID().toString().replace("-", "");
        Path published = outputPath.resolve(id + "." + extension);
        try (var conversionWorkspace =
                new ConversionWorkspace(outputPath, conversionSettings.getTimeoutSeconds())) {
            Path output = conversionWorkspace.path("result." + extension);
            operation.run(conversionWorkspace, output);
            conversionWorkspace.checkDeadline();
            if (!Files.isRegularFile(output) || Files.size(output) == 0)
                throw new ConversionException(
                        "Conversion did not produce a valid output. Check the source file and try again.");
            if (Files.size(output) > outputLimit())
                throw new ConversionException(
                        "Converted output exceeds the server's output size limit.");
            Files.move(output, published);
            log.debug(
                    "Published conversion {} to {} in {}ms",
                    id,
                    extension,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return new ConvertedFile(
                    id,
                    published,
                    UploadValidation.outputName(file, extension),
                    conversionWorkspace.warnings());
        } catch (AppException e) {
            log.warn("Conversion to {} rejected: {}", extension, e.getMessage());
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(published);
            log.warn(
                    "Conversion to {} failed for {}",
                    extension,
                    UploadValidation.displayName(file),
                    e);
            throw new ConversionException(
                    "The file could not be converted. Check that its contents match the format and that it is not corrupt or password-protected.");
        }
    }

    @FunctionalInterface
    private interface Operation {
        void run(ConversionWorkspace conversionWorkspace, Path output) throws Exception;
    }

    public record ConvertedFile(
            String fileId, Path path, String outputName, List<String> warnings) {
        public ConvertedFile {
            warnings = List.copyOf(warnings);
        }

        public ConvertedFile(String fileId, Path path, String outputName) {
            this(fileId, path, outputName, List.of());
        }
    }
}
