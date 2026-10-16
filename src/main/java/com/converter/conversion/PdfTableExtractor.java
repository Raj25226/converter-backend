package com.converter.conversion;

import com.converter.exception.ConversionException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.contentstream.operator.color.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

/** Uses glyph coordinates instead of whitespace, preserving column gaps and page boundaries. */
public final class PdfTableExtractor extends PDFTextStripper {
    private final ConversionSettings conversionSettings;
    private final List<TextPosition> positions = new ArrayList<>();
    private int characters;
    private int processedCharacters;
    private final Map<TextPosition, GlyphStyle> glyphStyles = new IdentityHashMap<>();
    private ConversionWorkspace conversionWorkspace;

    public PdfTableExtractor(ConversionSettings conversionSettings) throws IOException {
        this.conversionSettings = conversionSettings;
        setSortByPosition(true);
        setSuppressDuplicateOverlappingText(true);
        addOperator(new SetNonStrokingDeviceRGBColor(this));
        addOperator(new SetNonStrokingDeviceCMYKColor(this));
        addOperator(new SetNonStrokingDeviceGrayColor(this));
        addOperator(new SetNonStrokingColorSpace(this));
        addOperator(new SetNonStrokingColor(this));
        addOperator(new SetNonStrokingColorN(this));
        addOperator(new SetStrokingDeviceRGBColor(this));
        addOperator(new SetStrokingDeviceCMYKColor(this));
        addOperator(new SetStrokingDeviceGrayColor(this));
        addOperator(new SetStrokingColorSpace(this));
        addOperator(new SetStrokingColor(this));
        addOperator(new SetStrokingColorN(this));
    }

    @Override
    protected void processTextPosition(TextPosition position) {
        conversionWorkspace.checkDeadline();
        if (position.getUnicode() != null) processedCharacters += position.getUnicode().length();
        if (processedCharacters > conversionSettings.getMaxExtractedCharacters())
            throw new ConversionException(
                    "PDF contains too much text to extract safely. Split it into smaller documents.");
        var state = getGraphicsState();
        var color =
                state.getTextState().getRenderingMode().isFill()
                        ? state.getNonStrokingColor()
                        : state.getStrokingColor();
        int rgb;
        try {
            rgb = color.toRGB();
        } catch (IOException e) {
            throw new ConversionException("The PDF text color could not be decoded reliably.");
        }
        glyphStyles.put(position, GlyphStyle.of(position, rgb));
        super.processTextPosition(position);
    }

    @Override
    protected void writeString(String text, List<TextPosition> glyphs) {
        for (TextPosition glyph : glyphs) {
            if (glyph.getUnicode() == null || glyph.getUnicode().isEmpty()) continue;
            characters += glyph.getUnicode().length();
            if (characters > conversionSettings.getMaxExtractedCharacters())
                throw new ConversionException(
                        "PDF contains too much text to extract safely. Split it into smaller documents.");
            positions.add(glyph);
        }
    }

    public void writeWorkbook(PDDocument pdf, Path output, ConversionWorkspace conversionWorkspace)
            throws IOException {
        this.conversionWorkspace = conversionWorkspace;
        try (var workbook = new XSSFWorkbook()) {
            var textStyle = workbook.createCellStyle();
            textStyle.setWrapText(true);
            textStyle.setVerticalAlignment(VerticalAlignment.TOP);
            Map<GlyphStyle, XSSFFont> fonts = new HashMap<>();
            boolean found = false;
            int cells = 0;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                conversionWorkspace.checkDeadline();
                positions.clear();
                glyphStyles.clear();
                setStartPage(page);
                setEndPage(page);
                getText(pdf);
                List<List<TextPosition>> lines = lines();
                List<List<Segment>> rows =
                        lines.stream().map(this::segments).filter(row -> !row.isEmpty()).toList();
                if (rows.isEmpty()) continue;
                found = true;
                var sheet = workbook.createSheet("Page " + page);
                List<Segment> anchors =
                        rows.stream().max(Comparator.comparingInt(List::size)).orElseThrow();
                if (anchors.size() > conversionSettings.getMaxPdfTableColumns())
                    throw new ConversionException(
                            "PDF contains too many columns to extract safely.");
                double[] boundaries = new double[Math.max(0, anchors.size() - 1)];
                for (int i = 0; i < boundaries.length; i++)
                    boundaries[i] = (anchors.get(i).endX + anchors.get(i + 1).startX) / 2;
                int[] widths = new int[anchors.size()];
                int rowNumber = 0;
                for (var source : rows) {
                    conversionWorkspace.checkDeadline();
                    Map<Integer, StringBuilder> values = new TreeMap<>();
                    Map<Integer, List<Span>> spans = new TreeMap<>();
                    for (var segment : source) {
                        int column = 0;
                        while (column < boundaries.length && segment.startX > boundaries[column])
                            column++;
                        values.computeIfAbsent(column, key -> new StringBuilder());
                        if (!values.get(column).isEmpty()) values.get(column).append(' ');
                        int offset = values.get(column).length();
                        values.get(column).append(segment.text);
                        for (var span : segment.spans)
                            spans.computeIfAbsent(column, key -> new ArrayList<>())
                                    .add(
                                            new Span(
                                                    offset + span.start,
                                                    offset + span.end,
                                                    span.style));
                    }
                    Map<Integer, List<String>> parts = new TreeMap<>();
                    int count = 1;
                    for (var entry : values.entrySet()) {
                        List<String> split = splitCell(entry.getValue().toString());
                        parts.put(entry.getKey(), split);
                        count = Math.max(count, split.size());
                    }
                    for (int part = 0; part < count; part++) {
                        if (rowNumber >= conversionSettings.getMaxPdfTableRows())
                            throw new ConversionException(
                                    "PDF contains too many rows to extract safely.");
                        var row = sheet.createRow(rowNumber++);
                        for (var entry : parts.entrySet()) {
                            if (part >= entry.getValue().size()) continue;
                            String value = entry.getValue().get(part);
                            int column = entry.getKey();
                            if (++cells > conversionSettings.getMaxSpreadsheetCells())
                                throw new ConversionException(
                                        "PDF contains too many cells to extract safely.");
                            var cell = row.createCell(column);
                            cell.setCellStyle(textStyle);
                            // A PDF contains displayed values, not source types. Keep IDs, dates,
                            // currency,
                            // long integers and formula-looking strings exact instead of guessing
                            // numeric types.
                            var rich = new XSSFRichTextString(value);
                            int chunkStart =
                                    entry.getValue().subList(0, part).stream()
                                            .mapToInt(String::length)
                                            .sum();
                            for (var span : spans.getOrDefault(column, List.of())) {
                                int start = Math.max(0, span.start - chunkStart),
                                        end = Math.min(value.length(), span.end - chunkStart);
                                if (start >= end) continue;
                                var font = fonts.get(span.style);
                                if (font == null) {
                                    if (fonts.size() >= conversionSettings.getMaxPdfStyles())
                                        throw new ConversionException(
                                                "PDF contains too many distinct text styles to extract safely.");
                                    font = workbook.createFont();
                                    font.setFontName(span.style.family);
                                    font.setFontHeight(span.style.size);
                                    font.setBold(span.style.bold);
                                    font.setItalic(span.style.italic);
                                    int rgb = span.style.rgb;
                                    font.setColor(
                                            new XSSFColor(
                                                    new byte[] {
                                                        (byte) (rgb >> 16),
                                                        (byte) (rgb >> 8),
                                                        (byte) rgb
                                                    }));
                                    fonts.put(span.style, font);
                                }
                                rich.applyFont(start, end, font);
                            }
                            cell.setCellValue(rich);
                            double fontHeight =
                                    spans.getOrDefault(column, List.of()).stream()
                                            .mapToDouble(span -> span.style.size)
                                            .max()
                                            .orElse(11);
                            row.setHeightInPoints(
                                    (float) Math.max(row.getHeightInPoints(), fontHeight + 4));
                            widths[column] =
                                    Math.max(
                                            widths[column],
                                            Math.min(
                                                    value.length(),
                                                    conversionSettings.getTableMaxColumnWidth()));
                        }
                    }
                }
                for (int column = 0; column < widths.length; column++)
                    sheet.setColumnWidth(
                            column,
                            Math.max(
                                            conversionSettings.getTableMinColumnWidth(),
                                            widths[column]
                                                    + conversionSettings.getTableColumnPadding())
                                    * 256);
            }
            if (!found)
                throw new ConversionException(
                        "This PDF has no extractable text. Scanned documents require OCR, which this service does not provide.");
            try (var stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }
    }

    private List<List<TextPosition>> lines() {
        var sorted =
                positions.stream()
                        .sorted(
                                Comparator.comparingDouble(TextPosition::getYDirAdj)
                                        .thenComparingDouble(TextPosition::getXDirAdj))
                        .toList();
        List<List<TextPosition>> rows = new ArrayList<>();
        double baseline = -Double.MAX_VALUE;
        for (var glyph : sorted) {
            if (rows.isEmpty()
                    || Math.abs(glyph.getYDirAdj() - baseline)
                            > conversionSettings.getTableLineTolerance()) {
                rows.add(new ArrayList<>());
                baseline = glyph.getYDirAdj();
            }
            rows.getLast().add(glyph);
        }
        rows.forEach(row -> row.sort(Comparator.comparingDouble(TextPosition::getXDirAdj)));
        return rows;
    }

    private List<Segment> segments(List<TextPosition> glyphs) {
        List<Segment> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        float start = 0, end = 0;
        for (var glyph : glyphs) {
            float gap = glyph.getXDirAdj() - end;
            float space =
                    Math.max(conversionSettings.getTableMinSpaceWidth(), glyph.getWidthOfSpace());
            if (!text.isEmpty()
                    && gap
                            > Math.max(
                                    conversionSettings.getTableMinColumnGap(),
                                    space * conversionSettings.getTableColumnGapFactor())) {
                addSegment(result, start, end, text.toString(), spans);
                text.setLength(0);
                spans = new ArrayList<>();
            }
            if (text.isEmpty()) start = glyph.getXDirAdj();
            else if (gap > space * conversionSettings.getTableWordGapFactor()
                    && !Character.isWhitespace(text.charAt(text.length() - 1))) text.append(' ');
            String value = glyph.getUnicode().replaceAll("[\\p{Cntrl}&&[^\\t]]", "");
            int offset = text.length();
            text.append(value);
            spans.add(new Span(offset, text.length(), glyphStyles.get(glyph)));
            end = Math.max(end, glyph.getXDirAdj() + glyph.getWidthDirAdj());
        }
        addSegment(result, start, end, text.toString(), spans);
        return result;
    }

    private List<String> splitCell(String value) {
        List<String> parts = new ArrayList<>();
        for (int offset = 0; offset < value.length(); ) {
            int end = Math.min(value.length(), offset + 32_767);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            parts.add(value.substring(offset, end));
            offset = end;
        }
        return parts.isEmpty() ? List.of("") : parts;
    }

    private void addSegment(
            List<Segment> result, float start, float end, String text, List<Span> spans) {
        if (text.isBlank()) return;
        result.add(new Segment(start, end, text, List.copyOf(spans)));
    }

    private record Segment(float startX, float endX, String text, List<Span> spans) {}

    private record Span(int start, int end, GlyphStyle style) {}

    private record GlyphStyle(String family, double size, boolean bold, boolean italic, int rgb) {
        static GlyphStyle of(TextPosition position, int rgb) {
            String name = position.getFont().getName().replaceFirst("^[A-Z]{6}\\+", "");
            String lower = name.toLowerCase(Locale.ROOT);
            var descriptor = position.getFont().getFontDescriptor();
            boolean bold =
                    lower.contains("bold")
                            || (descriptor != null && descriptor.getFontWeight() >= 700);
            boolean italic =
                    lower.contains("italic")
                            || lower.contains("oblique")
                            || (descriptor != null && descriptor.isItalic());
            String family =
                    descriptor != null && descriptor.getFontFamily() != null
                            ? descriptor.getFontFamily()
                            : name;
            if (family.startsWith("Times")) family = "Times New Roman";
            else if (family.startsWith("Helvetica")) family = "Arial";
            else if (family.startsWith("Courier")) family = "Courier New";
            double size = position.getFontSizeInPt();
            if (!Double.isFinite(size) || size <= 0 || size > 409)
                throw new ConversionException(
                        "PDF font size cannot be represented reliably in Excel.");
            return new GlyphStyle(family, size, bold, italic, rgb);
        }
    }
}
