package com.converter.conversion;

import com.converter.exception.ConversionException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Checks glyph and embedded raster budgets before handing PDFs to a native importer. */
public final class PdfPreflight extends PDFTextStripper {
    private final ConversionSettings conversionSettings;
    private final ConversionWorkspace conversionWorkspace;
    private int characters;
    private final Set<Object> resources = Collections.newSetFromMap(new IdentityHashMap<>());

    public PdfPreflight(
            ConversionSettings conversionSettings, ConversionWorkspace conversionWorkspace)
            throws IOException {
        this.conversionSettings = conversionSettings;
        this.conversionWorkspace = conversionWorkspace;
    }

    @Override
    protected void processTextPosition(TextPosition position) {
        conversionWorkspace.checkDeadline();
        if (position.getUnicode() != null) characters += position.getUnicode().length();
        if (characters > conversionSettings.getMaxExtractedCharacters()) {
            throw new ConversionException(
                    "PDF contains too much text to extract safely. Split it into smaller documents.");
        }
        super.processTextPosition(position);
    }

    public String inspect(PDDocument pdf) throws IOException {
        return inspectPages(pdf).stream().map(Page::text).collect(Collectors.joining("\n"));
    }

    public List<Page> inspectPages(PDDocument pdf) throws IOException {
        boolean content = false;
        for (var page : pdf.getPages()) {
            conversionWorkspace.checkDeadline();
            content |= page.hasContents();
            inspectResources(page.getResources(), 0);
        }
        if (!content)
            throw new ConversionException("This PDF contains no page content to convert.");
        List<Page> result = new ArrayList<>();
        for (int index = 0; index < pdf.getNumberOfPages(); index++) {
            conversionWorkspace.checkDeadline();
            setStartPage(index + 1);
            setEndPage(index + 1);
            var page = pdf.getPage(index);
            var box = page.getCropBox();
            boolean rotated = Math.floorMod(page.getRotation(), 180) != 0;
            double width = (rotated ? box.getHeight() : box.getWidth()) * page.getUserUnit();
            double height = (rotated ? box.getWidth() : box.getHeight()) * page.getUserUnit();
            result.add(new Page(width, height, getText(pdf)));
        }
        return List.copyOf(result);
    }

    public record Page(double width, double height, String text) {}

    private void inspectResources(PDResources source, int depth) throws IOException {
        conversionWorkspace.checkDeadline();
        if (source == null || !resources.add(source.getCOSObject())) return;
        if (depth > 32) throw new ConversionException("PDF contains excessively nested graphics.");
        for (var name : source.getXObjectNames()) {
            conversionWorkspace.checkDeadline();
            var object = source.getXObject(name);
            if (object instanceof PDImageXObject image)
                UploadValidation.dimensions(
                        image.getWidth(), image.getHeight(), conversionSettings);
            else if (object instanceof PDFormXObject form)
                inspectResources(form.getResources(), depth + 1);
        }
    }
}
