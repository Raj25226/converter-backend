package com.converter.conversion;

import com.converter.exception.ConversionException;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;

/**
 * PDF coordinates are relative to the page, never to Writer's synthetic paragraph or column
 * margins.
 */
public final class PdfDocxLayout {
    private PdfDocxLayout() {}

    public static void normalize(
            Path input,
            Path output,
            ConversionSettings conversionSettings,
            ConversionWorkspace conversionWorkspace)
            throws Exception {
        try (var source = new ZipFile(input.toFile());
                var target = new ZipOutputStream(Files.newOutputStream(output))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                conversionWorkspace.checkDeadline();
                var entry = entries.nextElement();
                target.putNextEntry(new ZipEntry(entry.getName()));
                try (var stream = source.getInputStream(entry)) {
                    if (entry.getName().equals("word/document.xml")) {
                        if (entry.getSize()
                                > conversionSettings.getOfficeMaxTextMb() * 1024L * 1024)
                            throw new ConversionException(
                                    "PDF layout contains too much document markup to process safely.");
                        normalizeDocument(stream, target);
                    } else {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = stream.read(buffer)) != -1) {
                            conversionWorkspace.checkDeadline();
                            target.write(buffer, 0, length);
                        }
                    }
                }
                target.closeEntry();
            }
        }
    }

    private static void normalizeDocument(InputStream source, OutputStream target)
            throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(source);
        String drawing = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
        for (String name : new String[] {"positionH", "positionV"}) {
            var nodes = document.getElementsByTagNameNS(drawing, name);
            for (int i = 0; i < nodes.getLength(); i++)
                ((Element) nodes.item(i)).setAttribute("relativeFrom", "page");
        }
        var shapes = document.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "shape");
        for (int i = 0; i < shapes.getLength(); i++) {
            var shape = (Element) shapes.item(i);
            shape.setAttribute(
                    "style",
                    shape.getAttribute("style")
                            + ";mso-position-horizontal-relative:page;mso-position-vertical-relative:page");
        }
        var transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        transformers.newTransformer().transform(new DOMSource(document), new StreamResult(target));
    }
}
