package com.converter.feature;

import com.converter.exception.AppException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;

public enum FeatureFlag {
    LOGIN("login"),
    SIGNUP("signup"),
    GOOGLE_OAUTH("google-oauth"),
    EMAIL_VERIFICATION("email-verification"),
    PASSWORD_RESET("password-reset"),
    PROFILE("profile"),
    PASSWORD_CHANGE("password-change"),
    HISTORY("history"),
    ANALYTICS_TRACKING("analytics-tracking"),
    ANALYTICS_DASHBOARD("analytics-dashboard"),
    DOWNLOADS("downloads"),
    BACKGROUND_JOBS("background-jobs"),
    CONVERSIONS("conversions"),
    CONVERSION_PDF_TO_WORD("conversion-pdf-to-word"),
    CONVERSION_WORD_TO_PDF("conversion-word-to-pdf"),
    CONVERSION_PDF_TO_EXCEL("conversion-pdf-to-excel"),
    CONVERSION_EXCEL_TO_PDF("conversion-excel-to-pdf"),
    CONVERSION_PPT_TO_PDF("conversion-ppt-to-pdf"),
    CONVERSION_JPG_TO_PNG("conversion-jpg-to-png"),
    CONVERSION_PNG_TO_JPG("conversion-png-to-jpg"),
    CONVERSION_JPG_TO_WEBP("conversion-jpg-to-webp"),
    CONVERSION_WEBP_TO_PNG("conversion-webp-to-png"),
    CONVERSION_HEIC_TO_JPG("conversion-heic-to-jpg"),
    CONVERSION_MP4_TO_MP3("conversion-mp4-to-mp3"),
    CONVERSION_MP4_TO_GIF("conversion-mp4-to-gif"),
    CONVERSION_WAV_TO_MP3("conversion-wav-to-mp3"),
    CONVERSION_ZIP_EXTRACT("conversion-zip-extract"),
    CONVERSION_FILE_COMPRESS("conversion-file-compress"),
    CONVERSION_IMAGE_COMPRESSOR("conversion-image-compressor");
    private final String key;

    FeatureFlag(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static FeatureFlag fromKey(String key) {
        return Arrays.stream(values())
                .filter(flag -> flag.key.equals(key))
                .findFirst()
                .orElseThrow(
                        () -> new AppException("Unknown feature flag.", HttpStatus.BAD_REQUEST));
    }
}
