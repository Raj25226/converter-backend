package com.converter.conversion;

import com.converter.exception.ConversionException;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.w3c.dom.Element;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipException;
import org.springframework.web.multipart.MultipartFile;

public final class UploadValidation {
    private UploadValidation() {}
    public static String extension(MultipartFile file, String... allowed) {
        if (file == null || file.isEmpty()) throw new ConversionException("Upload a non-empty file to convert.");
        String extension = Objects.requireNonNullElse(FilenameUtils.getExtension(file.getOriginalFilename()), "").toLowerCase(Locale.ROOT);
        if (!Set.of(allowed).contains(extension)) throw new ConversionException("Unsupported file extension. Expected: " + String.join(", ", allowed) + ".");
        return extension;
    }
    public static String displayName(MultipartFile file) {
        String name = FilenameUtils.getName(Objects.requireNonNullElse(file.getOriginalFilename(), "file"));
        name = Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank()) name = "file";
        return name.length() > 220 ? name.substring(0, 220) : name;
    }
    public static String outputName(MultipartFile file, String extension) {
        String base = FilenameUtils.getBaseName(displayName(file));
        return (base.isBlank() ? "file" : base) + "-converted." + extension;
    }
    public static void pdfHeader(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            if (!Arrays.equals(stream.readNBytes(5), "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new ConversionException("This file is not a valid PDF.");
            }
        }
    }
    public static void image(Path file, Set<String> formats, ConversionSettings settings) throws IOException {
        try (var stream = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new ConversionException("This file is not a supported image.");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!formats.contains(format)) throw new ConversionException("The image content does not match the selected input format.");
                dimensions(reader.getWidth(0), reader.getHeight(0), settings);
            } finally { reader.dispose(); }
        }
    }
    public static void dimensions(long width, long height, ConversionSettings settings) {
        if (width < 1 || height < 1 || width > settings.getMaxImageDimension() || height > settings.getMaxImageDimension()
                || width * height > settings.getMaxImagePixels()) throw new ConversionException("Image dimensions exceed the server's image size limit.");
    }
    public static void office(Path file, String extension, ConversionSettings settings, ConversionWorkspace workspace) throws IOException {
        if (Set.of("doc", "xls", "ppt").contains(extension)) {
            try (var fs = new POIFSFileSystem(file.toFile(), true)) {
                var root = fs.getRoot();
                boolean expected = switch (extension) {
                    case "doc" -> root.hasEntry("WordDocument");
                    case "xls" -> root.hasEntry("Workbook") || root.hasEntry("Book");
                    default -> root.hasEntry("PowerPoint Document");
                };
                if (root.hasEntry("EncryptedPackage")) throw new ConversionException("Password-protected Office files are not supported.");
                if (!expected) throw new ConversionException("The Office file content does not match its extension.");
            } catch (ConversionException e) { throw e; }
            catch (Exception e) { throw new ConversionException("This legacy Office file is invalid or password-protected."); }
            return;
        }
        String required = switch (extension) { case "docx" -> "word/document.xml"; case "xlsx" -> "xl/workbook.xml"; default -> "ppt/presentation.xml"; };
        try (var zip = new ZipFile(file.toFile())) {
            if (zip.getEntry(required) == null) throw new ConversionException("The Office file content does not match its extension.");
            int count = 0; long total = 0; int[] cells = {0}; Set<String> names = new HashSet<>();
            var entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                workspace.checkDeadline(); var entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || name.startsWith("/") || name.contains("\\") || name.contains(":") || Arrays.asList(name.split("/")).contains("..")) {
                    throw new ConversionException("Office package contains duplicate or unsafe paths.");
                }
                if (++count > 10_000) throw new ConversionException("Office document contains too many embedded parts.");
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) throw new ConversionException("Macro-enabled Office files are not supported.");
                if (entry.isDirectory()) continue;
                long size = 0;
                try (var input = zip.getInputStream(entry)) {
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        workspace.checkDeadline(); size += length; total += length;
                        if (size > settings.getOfficeMaxEntryMb() * 1024L * 1024 || total > settings.getMaxOfficeExpandedMb() * 1024L * 1024) {
                            throw new ConversionException("Office document expands beyond the server's safe size limit.");
                        }
                    }
                }
                if (extension.equals("xlsx") && entry.getName().startsWith("xl/worksheets/") && entry.getName().endsWith(".xml")) {
                    try (var input = zip.getInputStream(entry)) { countSpreadsheetCells(input, cells, settings, workspace); }
                }
                if (entry.getName().endsWith(".rels")) {
                    if (size > settings.getMaxOfficeRelationshipsMb() * 1024L * 1024) throw new ConversionException("Office relationships are too large.");
                    try (var input = zip.getInputStream(entry)) { rejectExternalResources(input); }
                }
            }
        } catch (ZipException e) { throw new ConversionException("This Office file is corrupt or password-protected."); }
    }
    private static void countSpreadsheetCells(InputStream input, int[] cells, ConversionSettings settings, ConversionWorkspace workspace) {
        try {
            var factory = javax.xml.parsers.SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, ""); parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.parse(input, new org.xml.sax.helpers.DefaultHandler() {
                @Override public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
                    workspace.checkDeadline();
                    if (localName.equals("c") && ++cells[0] > settings.getMaxSpreadsheetCells()) throw new ConversionException("Spreadsheet contains too many cells. Split it into smaller workbooks.");
                }
            });
        } catch (ConversionException e) { throw e; }
        catch (Exception e) { throw new ConversionException("The spreadsheet contains invalid XML data."); }
    }

    private static void rejectExternalResources(InputStream input) {
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(input);
            var relationships = document.getElementsByTagNameNS("*", "Relationship");
            for (int i = 0; i < relationships.getLength(); i++) {
                Element relation = (Element) relationships.item(i);
                if ("External".equalsIgnoreCase(relation.getAttribute("TargetMode")) && !relation.getAttribute("Type").endsWith("/hyperlink")) {
                    throw new ConversionException("Office files with externally linked content are not supported. Embed the content before uploading.");
                }
            }
        } catch (ConversionException e) { throw e; }
        catch (Exception e) { throw new ConversionException("The Office document contains invalid relationships."); }
    }
}
