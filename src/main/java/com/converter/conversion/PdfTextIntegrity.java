package com.converter.conversion;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** Ignores layout whitespace and expands compatibility ligatures, but never tolerates lost letters. */
public final class PdfTextIntegrity {
    private PdfTextIntegrity() { }
    public static boolean containsText(String source, String converted) {
        var expected = characters(source); var actual = characters(converted);
        return expected.entrySet().stream().allMatch(e -> actual.getOrDefault(e.getKey(), 0) >= e.getValue());
    }
    private static Map<Integer, Integer> characters(String text) {
        Map<Integer, Integer> result = new HashMap<>();
        Normalizer.normalize(text, Normalizer.Form.NFKC).codePoints()
                .filter(c -> !Character.isWhitespace(c) && !Character.isISOControl(c) && c != 0x00AD)
                .forEach(c -> result.merge(c, 1, Integer::sum));
        return result;
    }
}
