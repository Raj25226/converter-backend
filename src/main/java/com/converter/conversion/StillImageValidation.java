package com.converter.conversion;

import com.converter.exception.ConversionException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Detects animation without decoding or allocating any frames. */
public final class StillImageValidation {
    private StillImageValidation() { }
    public static void check(Path path, String extension, ConversionWorkspace workspace) throws IOException {
        if (!extension.equals("png") && !extension.equals("webp")) return;
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            boolean png = extension.equals("png");
            file.seek(png ? 8 : 12);
            while (file.getFilePointer() + 8 <= file.length()) {
                workspace.checkDeadline();
                long length; String type;
                if (png) { length = Integer.toUnsignedLong(file.readInt()); type = readType(file); }
                else { type = readType(file); length = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt())); }
                if (type.equals("acTL") || type.equals("ANIM") || type.equals("ANMF")) {
                    throw new ConversionException("Animated images cannot be converted to a still image without losing frames. Upload a still image.");
                }
                long payload = file.getFilePointer();
                long next = payload + length + (png ? 4 : length % 2);
                if (next > file.length()) throw new ConversionException("The image contains a truncated or invalid data chunk.");
                if (!png && type.equals("VP8X") && length >= 1 && (file.readUnsignedByte() & 2) != 0) {
                    throw new ConversionException("Animated images cannot be converted to a still image without losing frames. Upload a still image.");
                }
                file.seek(next);
                if (png && type.equals("IEND")) break;
            }
        }
    }
    private static String readType(RandomAccessFile file) throws IOException {
        byte[] bytes = new byte[4]; file.readFully(bytes); return new String(bytes, StandardCharsets.US_ASCII);
    }
}
