package com.converter.conversion;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "conversion")
public class ConversionSettings {
    public enum PdfVerification { STRUCTURAL, DEEP }
    @NotNull private PdfVerification pdfVerification = PdfVerification.STRUCTURAL;
    @Min(1) @Max(16) private int maxConcurrent;
    @Min(1) @Max(600) private int timeoutSeconds;
    @Min(1) @Max(1024) private int maxOutputSizeMb;
    @Min(1) private int maxPdfPages;
    @Min(1) @Max(10000) private int maxPdfStyles;
    @Min(1) private int maxExtractedCharacters;
    @Min(1) private long maxImagePixels;
    @Min(1) private int maxImageDimension;
    @Min(1) private int maxSpreadsheetCells;
    @Min(1) private int maxOfficeExpandedMb;
    @Min(1) private int maxZipEntries;
    @Min(1) private int maxZipEntryMb;
    @Min(1) private int maxZipExpandedMb;
    @NotBlank private String officeBinary;
    @NotBlank private String imageMagickBinary;
    private String imageSrgbProfile;
    @NotBlank private String ffmpegBinary;
    @NotBlank private String ffprobeBinary;
    private boolean nativeHealthEnabled;

    @Min(1) private int nativeDiagnosticBytes;
    @Min(1) private int nativePollIntervalMs;
    @Min(1) private int nativeDrainTimeoutMs;
    @Min(1) private int nativeTerminationTimeoutSeconds;
    @Min(1) private int nativeProbeTimeoutMs;
    @Min(1) private long nativeHealthCacheSeconds;
    @NotBlank private String officeVclPlugin;
    @DecimalMin("0.0") @DecimalMax("1.0") private double officeMinInflateRatio;
    @Min(1) private int officeMaxEntryMb;
    @Min(1) private int officeMaxTextMb;
    @Min(1) @Max(100) private int jpegQuality;
    @Min(1) @Max(100) private int webpQuality;
    @Min(1) @Max(100) private int compressionJpegQuality;
    @NotBlank private String imageMemoryLimit;
    @NotBlank private String imageMapLimit;
    @NotBlank private String imageDiskLimit;
    @Min(1) private int nativeThreads;
    @Min(1) private int filterThreads;
    @NotBlank private String audioBitrate;
    @Min(1) private int audioSampleRate;
    @Min(1) private int gifDurationSeconds;
    @Min(1) private int gifFramesPerSecond;
    @Min(1) private int gifMaxDimension;
    @Min(1) private long ffmpegMaxAllocationBytes;
    @Min(1) private int archiveMaxNameLength;
    @Min(1) private int documentFontSize;
    @NotBlank private String documentFontFamily;
    @Min(0) private int documentParagraphSpacing;
    @Min(1) @Max(16384) private int maxPdfTableColumns;
    @Min(1) @Max(1048576) private int maxPdfTableRows;
    @Min(1) @Max(253) private int tableMaxColumnWidth;
    @Min(1) @Max(253) private int tableMinColumnWidth;
    @Min(0) @Max(2) private int tableColumnPadding;
    @DecimalMin("0.1") private double tableLineTolerance;
    @DecimalMin("0.1") private float tableMinSpaceWidth;
    @DecimalMin("0.1") private double tableMinColumnGap;
    @DecimalMin("0.1") private double tableColumnGapFactor;
    @DecimalMin("0.1") private double tableWordGapFactor;
    @Min(1) private int maxOfficeRelationshipsMb;
}
