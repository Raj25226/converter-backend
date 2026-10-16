package com.converter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Reads editable Word content, including positioned text boxes, without counting compatibility
 * fallbacks twice.
 */
final class DocxContent {
    static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    static Document read(Path file) throws Exception {
        try (var zip = new ZipFile(file.toFile());
                var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(input);
        }
    }

    static List<Element> runs(Document document) {
        var nodes = document.getElementsByTagNameNS(WORD, "r");
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element run = (Element) nodes.item(i);
            boolean fallback = false;
            for (Node parent = run.getParentNode();
                    parent != null;
                    parent = parent.getParentNode()) {
                if ("Fallback".equals(parent.getLocalName())) fallback = true;
            }
            if (!fallback && run.getElementsByTagNameNS(WORD, "t").getLength() > 0) result.add(run);
        }
        return result;
    }

    static String text(Element run) {
        var nodes = run.getElementsByTagNameNS(WORD, "t");
        var text = new StringBuilder();
        for (int i = 0; i < nodes.getLength(); i++) text.append(nodes.item(i).getTextContent());
        return text.toString();
    }

    static String attribute(Element run, String property, String attribute) {
        var nodes = run.getElementsByTagNameNS(WORD, property);
        return nodes.getLength() == 0
                ? ""
                : ((Element) nodes.item(0)).getAttributeNS(WORD, attribute);
    }

    static boolean enabled(Element run, String property) {
        if (run.getElementsByTagNameNS(WORD, property).getLength() == 0) return false;
        String value = attribute(run, property, "val");
        return !value.equals("false") && !value.equals("0");
    }
}
