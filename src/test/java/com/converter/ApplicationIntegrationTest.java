package com.converter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.converter.conversion.ConversionSettings;
import com.converter.conversion.JobSettings;
import com.converter.entity.Conversion;
import com.converter.entity.EmailToken;
import com.converter.entity.User;
import com.converter.exception.RateLimitException;
import com.converter.feature.FeatureFlag;
import com.converter.feature.FeatureFlagSettings;
import com.converter.repository.ConversionRepository;
import com.converter.repository.EmailTokenRepository;
import com.converter.repository.UserRepository;
import com.converter.service.ConversionJobService;
import com.converter.service.ConversionService;
import com.converter.service.FeatureFlagService;
import com.converter.service.QuotaService;
import com.converter.util.JwtUtil;
import java.awt.geom.Rectangle2D.Double;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:converter;MODE=PostgreSQL;NON_KEYWORDS=DAY;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "jwt.secret=integration-test-only-secret-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "jobs.poll-interval-ms=3600000",
            "conversion.native-health-enabled=false",
            "conversion.user-daily-limit=100",
            "file.max-size-mb=73",
            "file.expiry-minutes=17",
            "spring.mail.username=",
            "spring.mail.password=",
            "file.output-dir=/tmp/converter-integration/outputs",
            "file.upload-dir=/tmp/converter-integration/uploads"
        })
@AutoConfigureMockMvc
class ApplicationIntegrationTest {
    @org.junit.jupiter.api.BeforeEach
    void enableFeaturesForIntegrationFlows() {
        // Production seed defaults are verified independently in FeatureFlagMigrationTest.
        jdbcTemplate.update("UPDATE feature_flags SET enabled = TRUE");
    }

    @Autowired ApplicationContext applicationContext;
    @Autowired FeatureFlagService featureFlagService;
    @Autowired FeatureFlagSettings featureFlagSettings;

    private String featureAdminToken() {
        var admin =
                new User(
                        "feature-admin-" + UUID.randomUUID() + "@example.test",
                        null,
                        "Feature",
                        "Admin");
        admin.setRole(User.Role.ADMIN);
        admin = userRepository.saveAndFlush(admin);
        return jwtUtil.generateToken(admin);
    }

    @Test
    void featureFlagsArePublicButChangesRequireAdmin() throws Exception {
        mvc.perform(get("/api/features"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.login").value(true));
        mvc.perform(
                        patch("/api/admin/features/login")
                                .contentType("application/json")
                                .content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        var user =
                userRepository.saveAndFlush(
                        new User("flags-user@example.test", null, "Flags", "User"));
        mvc.perform(
                        patch("/api/admin/features/login")
                                .header("Authorization", "Bearer " + jwtUtil.generateToken(user))
                                .contentType("application/json")
                                .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        String auth = "Bearer " + featureAdminToken();
        mvc.perform(
                        patch("/api/admin/features/unknown")
                                .header("Authorization", auth)
                                .contentType("application/json")
                                .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        patch("/api/admin/features/login")
                                .header("Authorization", auth)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledLoginAndOauthAreEnforcedWithoutChargingQuotaAndCanBeReset() throws Exception {
        String auth = "Bearer " + featureAdminToken();
        try {
            mvc.perform(
                            patch("/api/admin/features/login")
                                    .header("Authorization", auth)
                                    .contentType("application/json")
                                    .content("{\"enabled\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.editable").value(true));
            Mockito.clearInvocations(quotaService);
            mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(post("/api/auth/google").contentType("application/json").content("{}"))
                    .andExpect(status().isServiceUnavailable());
            Mockito.verifyNoInteractions(quotaService);
            mvc.perform(get("/api/admin/features").header("Authorization", auth))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/features")).andExpect(jsonPath("$.login").value(false));
            Assertions.assertTrue(
                    jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM feature_flag_audit WHERE flag_key = 'login'",
                                    Integer.class)
                            > 0);
            mvc.perform(delete("/api/admin/features/login").header("Authorization", auth))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/features")).andExpect(jsonPath("$.login").value(true));
        } finally {
            jdbcTemplate.update("DELETE FROM feature_flags WHERE flag_key = 'login'");
        }
    }

    @Test
    void everyDisabledConversionRejectsBothSubmissionRoutesBeforeQuotaReservation()
            throws Exception {
        String[] tools = {
            "pdf-to-word",
            "word-to-pdf",
            "pdf-to-excel",
            "excel-to-pdf",
            "ppt-to-pdf",
            "jpg-to-png",
            "png-to-jpg",
            "jpg-to-webp",
            "webp-to-png",
            "heic-to-jpg",
            "mp4-to-mp3",
            "mp4-to-gif",
            "wav-to-mp3",
            "zip-extract",
            "file-compress"
        };
        try {
            for (String tool : tools) {
                String key = "conversion-" + tool;
                featureFlagService.update(key, false, 1L);
                Mockito.clearInvocations(quotaService);
                var file =
                        new MockMultipartFile(
                                "file", "test.bin", "application/octet-stream", new byte[] {1});
                mvc.perform(multipart("/api/convert/" + tool).file(file))
                        .andExpect(status().isServiceUnavailable());
                mvc.perform(
                                multipart("/api/jobs/" + tool)
                                        .file(file)
                                        .param("requestId", UUID.randomUUID().toString()))
                        .andExpect(status().isServiceUnavailable());
                Mockito.verifyNoInteractions(quotaService);
                featureFlagService.reset(key, 1L);
            }
        } finally {
            jdbcTemplate.update("DELETE FROM feature_flags WHERE flag_key LIKE 'conversion-%'");
        }
    }

    @Test
    void environmentModeIgnoresDatabaseOverridesAndRejectsAdminWrites() throws Exception {
        try {
            featureFlagService.update("signup", false, 1L);
            featureFlagSettings.setSource(FeatureFlagSettings.Source.ENVIRONMENT);
            Assertions.assertTrue(featureFlagService.enabled(FeatureFlag.SIGNUP));
            mvc.perform(
                            patch("/api/admin/features/signup")
                                    .header("Authorization", "Bearer " + featureAdminToken())
                                    .contentType("application/json")
                                    .content("{\"enabled\":true}"))
                    .andExpect(status().isConflict());
        } finally {
            featureFlagSettings.setSource(FeatureFlagSettings.Source.DATABASE);
            jdbcTemplate.update("DELETE FROM feature_flags WHERE flag_key = 'signup'");
        }
    }

    @Test
    void accountAnalyticsHistoryAndDownloadFlagsAreEnforced() throws Exception {
        String auth = "Bearer " + featureAdminToken();
        String[][] cases = {
            {"signup", "POST", "/api/auth/signup"},
            {"google-oauth", "POST", "/api/auth/google"},
            {"email-verification", "POST", "/api/auth/resend-verification"},
            {"email-verification", "GET", "/api/auth/verify-email?token=fixture"},
            {"password-reset", "POST", "/api/auth/forgot-password"},
            {"password-reset", "POST", "/api/auth/reset-password"},
            {"profile", "PUT", "/api/auth/profile"},
            {"password-change", "POST", "/api/auth/change-password"},
            {"analytics-tracking", "POST", "/api/analytics/track-visit"},
            {"analytics-dashboard", "GET", "/api/analytics/stats"},
            {"history", "GET", "/api/user/history"},
            {"downloads", "GET", "/api/download/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        };
        for (String[] item : cases) {
            try {
                featureFlagService.update(item[0], false, 1L);
                var request =
                        switch (item[1]) {
                            case "POST" -> post(item[2]);
                            case "PUT" -> put(item[2]);
                            default -> get(item[2]);
                        };
                mvc.perform(
                                request.header("Authorization", auth)
                                        .contentType("application/json")
                                        .content("{}"))
                        .andExpect(status().isServiceUnavailable());
            } finally {
                featureFlagService.reset(item[0], 1L);
            }
        }
    }

    @Test
    void backgroundFlagBlocksNewJobsButAllowsRecoveryAndCancellation() throws Exception {
        var submitted =
                submitJob(
                        "file-compress",
                        UUID.randomUUID().toString(),
                        null,
                        "flags-note.txt",
                        "fixture".getBytes());
        String id = submitted.path("jobId").asText();
        try {
            featureFlagService.update("background-jobs", false, 1L);
            Mockito.clearInvocations(quotaService);
            mvc.perform(
                            multipart("/api/jobs/file-compress")
                                    .file(
                                            new MockMultipartFile(
                                                    "file",
                                                    "note.txt",
                                                    "text/plain",
                                                    new byte[] {1}))
                                    .param("requestId", UUID.randomUUID().toString()))
                    .andExpect(status().isServiceUnavailable());
            Mockito.verifyNoInteractions(quotaService);
            mvc.perform(get("/api/jobs/" + id)).andExpect(status().isOk());
            mvc.perform(delete("/api/jobs/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("cancelled"));
        } finally {
            featureFlagService.reset("background-jobs", 1L);
        }
    }

    @Test
    void defaultInMemorySecurityUserIsNotCreated() {
        Assertions.assertTrue(
                applicationContext.getBeansOfType(UserDetailsService.class).isEmpty());
    }

    @Autowired MockMvc mvc;
    @Autowired ConversionJobService conversionJobService;
    @Autowired JobSettings jobSettings;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager platformTransactionManager;
    @Autowired ConversionService conversionService;
    @Autowired ConversionSettings conversionSettings;

    @Autowired
    @Qualifier("uploadPath")
    Path uploadPath;

    @Autowired UserRepository userRepository;
    @Autowired JwtUtil jwtUtil;
    @Autowired EmailTokenRepository emailTokenRepository;
    @Autowired ConversionRepository conversionRepository;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean
    QuotaService
            quotaService; // Reservation SQL needs PostgreSQL; H2 validates schema and HTTP wiring.

    @Test
    void missingUploadIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/convert/pdf-to-word"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-ID"));
    }

    @Test
    void wrongMethodAndMediaTypeAreClientErrors() throws Exception {
        mvc.perform(get("/api/convert/pdf-to-word")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/convert/pdf-to-word").contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void everyConversionRouteProducesDownloadableValidatedOutput() throws Exception {
        Path fixtures = Files.createTempDirectory("converter-api-fixtures-");
        try {
            var runner = TestSettings.runner();
            String magick = TestSettings.settings().getImageMagickBinary();
            Map<String, Path> inputs = new LinkedHashMap<>();
            var docx = fixtures.resolve("word.docx");
            try (var doc = new XWPFDocument();
                    var out = Files.newOutputStream(docx)) {
                doc.createParagraph().createRun().setText("Invoice details");
                doc.write(out);
            }
            inputs.put("word-to-pdf", docx);
            var xlsx = fixtures.resolve("sheet.xlsx");
            try (var book = new XSSFWorkbook();
                    var out = Files.newOutputStream(xlsx)) {
                book.createSheet("Sales")
                        .createRow(0)
                        .createCell(0)
                        .setCellValue("Invoice details");
                book.write(out);
            }
            inputs.put("excel-to-pdf", xlsx);
            var pptx = fixtures.resolve("slides.pptx");
            try (var deck = new XMLSlideShow();
                    var out = Files.newOutputStream(pptx)) {
                var text = deck.createSlide().createTextBox();
                text.setAnchor(new Double(30, 30, 500, 100));
                text.setText("Invoice details");
                deck.write(out);
            }
            inputs.put("ppt-to-pdf", pptx);
            var pdf = fixtures.resolve("text.pdf");
            try (var document = new PDDocument()) {
                var page = new PDPage();
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText("Invoice details");
                    stream.endText();
                }
                document.save(pdf.toFile());
            }
            inputs.put("pdf-to-word", pdf);
            inputs.put("pdf-to-excel", pdf);
            for (String ext : List.of("jpg", "png", "webp", "heic")) {
                runner.execute(
                        List.of(
                                magick,
                                "-size",
                                "24x16",
                                "gradient:red-blue",
                                fixtures.resolve("image." + ext).toString()),
                        fixtures,
                        30000,
                        null,
                        Long.MAX_VALUE);
            }
            inputs.put("jpg-to-png", fixtures.resolve("image.jpg"));
            inputs.put("jpg-to-webp", fixtures.resolve("image.jpg"));
            inputs.put("png-to-jpg", fixtures.resolve("image.png"));
            inputs.put("webp-to-png", fixtures.resolve("image.webp"));
            inputs.put("heic-to-jpg", fixtures.resolve("image.heic"));
            var video = fixtures.resolve("clip.mp4");
            runner.execute(
                    List.of(
                            "ffmpeg",
                            "-v",
                            "error",
                            "-y",
                            "-f",
                            "lavfi",
                            "-i",
                            "testsrc2=size=64x64:rate=10",
                            "-f",
                            "lavfi",
                            "-i",
                            "sine=frequency=440",
                            "-t",
                            "1",
                            "-c:v",
                            "mpeg4",
                            "-c:a",
                            "aac",
                            video.toString()),
                    fixtures,
                    30000,
                    null,
                    Long.MAX_VALUE);
            inputs.put("mp4-to-mp3", video);
            inputs.put("mp4-to-gif", video);
            var wav = fixtures.resolve("audio.wav");
            runner.execute(
                    List.of(
                            "ffmpeg",
                            "-v",
                            "error",
                            "-y",
                            "-f",
                            "lavfi",
                            "-i",
                            "sine=frequency=440",
                            "-t",
                            "1",
                            wav.toString()),
                    fixtures,
                    30000,
                    null,
                    Long.MAX_VALUE);
            inputs.put("wav-to-mp3", wav);
            var zip = fixtures.resolve("archive.zip");
            try (var archive = new ZipOutputStream(Files.newOutputStream(zip))) {
                archive.putNextEntry(new ZipEntry("note.txt"));
                archive.write("Invoice details".getBytes());
                archive.closeEntry();
            }
            inputs.put("zip-extract", zip);
            var text = fixtures.resolve("note.txt");
            Files.writeString(text, "Invoice details");
            inputs.put("file-compress", text);
            String smokeDirectory = System.getenv("CONVERSION_SMOKE_FIXTURES");
            if (smokeDirectory != null && !smokeDirectory.isBlank()) {
                var target = Path.of(smokeDirectory);
                Files.createDirectories(target);
                try (var files = Files.list(fixtures)) {
                    for (var source : files.toList())
                        Files.copy(
                                source,
                                target.resolve(source.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (var input : inputs.entrySet()) {
                var response =
                        mvc.perform(
                                        multipart("/api/convert/" + input.getKey())
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                input.getValue()
                                                                        .getFileName()
                                                                        .toString(),
                                                                "application/octet-stream",
                                                                Files.readAllBytes(
                                                                        input.getValue()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("success"))
                                .andReturn();
                var data = objectMapper.readTree(response.getResponse().getContentAsString());
                if (Set.of(
                                "pdf-to-word",
                                "pdf-to-excel",
                                "ppt-to-pdf",
                                "jpg-to-png",
                                "png-to-jpg",
                                "jpg-to-webp",
                                "webp-to-png",
                                "heic-to-jpg",
                                "mp4-to-mp3",
                                "mp4-to-gif",
                                "wav-to-mp3")
                        .contains(input.getKey())) {
                    Assertions.assertTrue(
                            data.path("warnings").isArray() && !data.path("warnings").isEmpty(),
                            input.getKey());
                }
                var download =
                        mvc.perform(get(URI.create(data.get("downloadUrl").asText()).getPath()))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andReturn();
                byte[] bytes = download.getResponse().getContentAsByteArray();
                String name = data.get("fileName").asText();
                Assertions.assertTrue(bytes.length > 0, input.getKey());
                if (name.endsWith(".pdf")) {
                    try (var result = Loader.loadPDF(bytes)) {
                        Assertions.assertTrue(result.getNumberOfPages() > 0);
                    }
                } else if (name.endsWith(".docx")) {
                    try (var result = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                        Assertions.assertFalse(result.getParagraphs().isEmpty());
                    }
                } else if (name.endsWith(".xlsx")) {
                    try (var result = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                        Assertions.assertTrue(result.getNumberOfSheets() > 0);
                    }
                } else if (name.endsWith(".mp3")) {
                    Assertions.assertTrue(
                            download.getResponse().getContentType().startsWith("audio/mpeg"));
                } else if (name.endsWith(".zip")) {
                    try (var result = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                        Assertions.assertEquals("note.txt", result.getNextEntry().getName());
                    }
                } else if (name.endsWith(".gz")) {
                    try (var result = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                        Assertions.assertEquals(
                                "Invoice details", new String(result.readAllBytes()));
                    }
                } else {
                    Assertions.assertNotNull(
                            ImageIO.read(new ByteArrayInputStream(bytes)), input.getKey());
                }
            }
        } finally {
            try (var paths = Files.walk(fixtures)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void profileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void analyticsRequiresAdmin() throws Exception {
        mvc.perform(get("/api/analytics/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void downloadsReportMissingFiles() throws Exception {
        mvc.perform(get("/api/download/" + "0".repeat(32))).andExpect(status().isNotFound());
    }

    @Test
    void invalidPdfProducesClientError() throws Exception {
        mvc.perform(
                        multipart("/api/convert/pdf-to-word")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "test.pdf",
                                                "application/pdf",
                                                "invalid data".getBytes())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void guestQuotaIsPublic() throws Exception {
        mvc.perform(get("/api/convert/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2));
    }

    @Test
    void adminConfigurationReflectsEffectiveConversionSettings() throws Exception {
        var admin = new User("config-admin@example.test", null, "Config", "Admin");
        admin.setRole(User.Role.ADMIN);
        admin = userRepository.saveAndFlush(admin);
        mvc.perform(
                        get("/api/admin/stats")
                                .header("Authorization", "Bearer " + jwtUtil.generateToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemConfiguration.guestDailyLimit").value(2))
                .andExpect(jsonPath("$.systemConfiguration.userDailyLimit").value(100))
                .andExpect(jsonPath("$.systemConfiguration.maxFileSizeMb").value(73))
                .andExpect(jsonPath("$.systemConfiguration.expiryMinutes").value(17));
    }

    @Test
    void revokedAdminRoleIsEffectiveForExistingToken() throws Exception {
        var user = new User("revoked@example.test", null, "Test", "Admin");
        user.setRole(User.Role.ADMIN);
        user.setEmailVerified(true);
        user = userRepository.saveAndFlush(user);
        String token = jwtUtil.generateToken(user);
        user.setRole(User.Role.USER);
        userRepository.saveAndFlush(user);
        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendedUserCannotUseExistingToken() throws Exception {
        var user =
                userRepository.saveAndFlush(
                        new User("suspended@example.test", null, "Test", "User"));
        String token = jwtUtil.generateToken(user);
        user.setActive(false);
        userRepository.saveAndFlush(user);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conversionCanBeDownloaded() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", bytes);
        var response =
                mvc.perform(
                                multipart("/api/convert/jpg-to-png")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "photo.jpg",
                                                        "image/jpeg",
                                                        bytes.toByteArray())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("success"))
                        .andReturn();
        String url =
                objectMapper
                        .readTree(response.getResponse().getContentAsString())
                        .get("downloadUrl")
                        .asText();
        var download =
                mvc.perform(get(URI.create(url).getPath()))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        Assertions.assertNotNull(
                ImageIO.read(
                        new ByteArrayInputStream(download.getResponse().getContentAsByteArray())));
    }

    @Test
    void passwordVersionRevokesExistingToken() throws Exception {
        var user =
                userRepository.saveAndFlush(new User("version@example.test", null, "Test", "User"));
        String token = jwtUtil.generateToken(user);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.saveAndFlush(user);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletingUserAlsoDeletesAssociatedRecords() throws Exception {
        var admin = new User("deleteadmin@example.test", null, "Test", "Admin");
        admin.setRole(User.Role.ADMIN);
        admin = userRepository.saveAndFlush(admin);
        var victim =
                userRepository.saveAndFlush(
                        new User("deletevictim@example.test", null, "Test", "User"));
        var email =
                emailTokenRepository.saveAndFlush(
                        new EmailToken(
                                victim,
                                "deletion-test",
                                EmailToken.TokenType.VERIFY_EMAIL,
                                Instant.now().plusSeconds(600)));
        var record = new Conversion();
        record.setUser(victim);
        record.setConverterType("test");
        record.setExpiresAt(Instant.now().plusSeconds(600));
        record = conversionRepository.saveAndFlush(record);
        mvc.perform(
                        delete("/api/admin/users/" + victim.getId())
                                .header("Authorization", "Bearer " + jwtUtil.generateToken(admin)))
                .andExpect(status().isOk());
        Assertions.assertFalse(userRepository.existsById(victim.getId()));
        Assertions.assertFalse(emailTokenRepository.existsById(email.getId()));
        Assertions.assertFalse(conversionRepository.existsById(record.getId()));
    }

    private JsonNode submitJob(
            String tool, String requestId, String token, String filename, byte[] bytes)
            throws Exception {
        var request =
                multipart("/api/jobs/" + tool)
                        .file(
                                new MockMultipartFile(
                                        "file", filename, "application/octet-stream", bytes))
                        .param("requestId", requestId);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return objectMapper.readTree(
                mvc.perform(request)
                        .andExpect(status().isAccepted())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private JsonNode awaitJob(String id, String expected) {
        var value = new AtomicReference<JsonNode>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            var body =
                                    mvc.perform(get("/api/jobs/" + id))
                                            .andExpect(status().isOk())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString();
                            var data = objectMapper.readTree(body);
                            Assertions.assertEquals(expected, data.path("status").asText());
                            value.set(data);
                        });
        return value.get();
    }

    @Test
    void jobSubmissionIsIdempotentAndResultSurvivesSeparateHttpRequests() throws Exception {
        Mockito.clearInvocations(quotaService);
        String request = UUID.randomUUID().toString();
        var job =
                submitJob(
                        "file-compress",
                        request,
                        null,
                        "note.txt",
                        "recoverable contents".getBytes());
        var duplicate =
                submitJob(
                        "file-compress",
                        request,
                        null,
                        "note.txt",
                        "recoverable contents".getBytes());
        Assertions.assertEquals(job.path("jobId"), duplicate.path("jobId"));
        Mockito.verify(quotaService, Mockito.times(1))
                .reserve(
                        ArgumentMatchers.isNull(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.eq(2));
        mvc.perform(get("/api/jobs/request/" + request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.path("jobId").asText()));
        conversionJobService.dispatch();
        var result = awaitJob(job.path("jobId").asText(), "succeeded");
        var download =
                mvc.perform(
                                get(
                                        URI.create(
                                                        result.path("result")
                                                                .path("downloadUrl")
                                                                .asText())
                                                .getPath()))
                        .andExpect(status().isOk())
                        .andReturn();
        try (var gzip =
                new GZIPInputStream(
                        new ByteArrayInputStream(download.getResponse().getContentAsByteArray()))) {
            Assertions.assertEquals("recoverable contents", new String(gzip.readAllBytes()));
        }
    }

    @Test
    void queuedJobsCanBeCancelledAndNeverProduceOutputs() throws Exception {
        var job =
                submitJob(
                        "file-compress",
                        UUID.randomUUID().toString(),
                        null,
                        "cancel.txt",
                        "cancel me".getBytes());
        String id = job.path("jobId").asText();
        mvc.perform(delete("/api/jobs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        conversionJobService.dispatch();
        conversionJobService.maintain();
        mvc.perform(get("/api/jobs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.result").doesNotExist());
        Assertions.assertFalse(Files.exists(uploadPath.resolve("jobs/" + id + ".upload")));
    }

    @Test
    void ownedJobsAreNotVisibleToOtherUsersOrAnonymousClients() throws Exception {
        var owner =
                userRepository.saveAndFlush(
                        new User("job-owner@example.test", null, "Job", "Owner"));
        var other =
                userRepository.saveAndFlush(
                        new User("job-other@example.test", null, "Job", "Other"));
        String token = jwtUtil.generateToken(owner);
        var job =
                submitJob(
                        "file-compress",
                        UUID.randomUUID().toString(),
                        token,
                        "owned.txt",
                        "private".getBytes());
        String path = "/api/jobs/" + job.path("jobId").asText();
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", "Bearer " + jwtUtil.generateToken(other)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).header("Authorization", "Bearer " + jwtUtil.generateToken(other)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/jobs")
                                .param("tool", "file-compress")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(job.path("jobId").asText()));
        mvc.perform(delete(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void fullQueueRejectsNewRequestsButAllowsIdempotentLookup() throws Exception {
        int previous = jobSettings.getMaxPending();
        jobSettings.setMaxPending(1);
        String id = null;
        try {
            String request = UUID.randomUUID().toString();
            var job = submitJob("file-compress", request, null, "queued.txt", "queued".getBytes());
            id = job.path("jobId").asText();
            submitJob("file-compress", request, null, "queued.txt", "queued".getBytes());
            mvc.perform(
                            multipart("/api/jobs/file-compress")
                                    .file(
                                            new MockMultipartFile(
                                                    "file",
                                                    "full.txt",
                                                    "text/plain",
                                                    "full".getBytes()))
                                    .param("requestId", UUID.randomUUID().toString()))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            if (id != null) mvc.perform(delete("/api/jobs/" + id));
            jobSettings.setMaxPending(previous);
        }
    }

    @Test
    void jobFailuresArePersistedAsStatusInsteadOfAnUploadHttpError() throws Exception {
        var job =
                submitJob(
                        "pdf-to-word",
                        UUID.randomUUID().toString(),
                        null,
                        "invalid.pdf",
                        "not a pdf".getBytes());
        conversionJobService.dispatch();
        var failed = awaitJob(job.path("jobId").asText(), "failed");
        Assertions.assertTrue(failed.path("error").asText().contains("valid PDF"));
    }

    @Test
    void queuedJobsCanBeClaimedByAnotherWorkerInstance() throws Exception {
        var job =
                submitJob(
                        "file-compress",
                        UUID.randomUUID().toString(),
                        null,
                        "restart.txt",
                        "stored upload".getBytes());
        var alternate =
                new ConversionJobService(
                        jdbcTemplate,
                        platformTransactionManager,
                        conversionService,
                        conversionSettings,
                        jobSettings,
                        objectMapper,
                        uploadPath);
        try {
            alternate.dispatch();
            awaitJob(job.path("jobId").asText(), "succeeded");
        } finally {
            alternate.close();
        }
    }

    @Test
    void historyPaginationIsBoundedAndStableForIdenticalTimestamps() throws Exception {
        var user =
                userRepository.saveAndFlush(
                        new User("paged-history@example.test", null, "Page", "Test"));
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            var record = new Conversion();
            record.setUser(user);
            record.setConverterType("test");
            record.setExpiresAt(Instant.now().plusSeconds(600));
            ids.add(conversionRepository.saveAndFlush(record).getId());
        }
        jdbcTemplate.update(
                "UPDATE conversions SET created_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now()),
                user.getId());
        String auth = "Bearer " + jwtUtil.generateToken(user);
        mvc.perform(get("/api/user/history").param("size", "2").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversions.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.conversions[0].id").value(ids.get(2).intValue()));
        mvc.perform(
                        get("/api/user/history")
                                .param("page", "1")
                                .param("size", "2")
                                .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.conversions.length()").value(1))
                .andExpect(jsonPath("$.conversions[0].id").value(ids.get(0).intValue()));
        mvc.perform(get("/api/user/history").param("size", "10000").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exhaustedQuotaDoesNotCreateJobOrStageAnUpload() throws Exception {
        long before =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversion_jobs", Long.class);
        Mockito.doThrow(new RateLimitException("Daily limit reached."))
                .when(quotaService)
                .reserve(
                        ArgumentMatchers.isNull(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.eq(2));
        try {
            mvc.perform(
                            multipart("/api/jobs/file-compress")
                                    .file(
                                            new MockMultipartFile(
                                                    "file",
                                                    "quota.txt",
                                                    "text/plain",
                                                    "rejected".getBytes()))
                                    .param("requestId", UUID.randomUUID().toString()))
                    .andExpect(status().isTooManyRequests());
            Assertions.assertEquals(
                    before,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM conversion_jobs", Long.class));
        } finally {
            Mockito.doNothing()
                    .when(quotaService)
                    .reserve(
                            ArgumentMatchers.isNull(),
                            ArgumentMatchers.anyString(),
                            ArgumentMatchers.eq(2));
        }
    }

    @Test
    void deletingCompletedHistoryInvalidatesRecoveredJobDownload() throws Exception {
        var user =
                userRepository.saveAndFlush(
                        new User("removed-job-result@example.test", null, "Removed", "Result"));
        String token = jwtUtil.generateToken(user);
        var accepted =
                submitJob(
                        "file-compress",
                        UUID.randomUUID().toString(),
                        token,
                        "remove.txt",
                        "remove this".getBytes());
        String id = accepted.path("jobId").asText();
        conversionJobService.dispatch();
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                Assertions.assertEquals(
                                        "succeeded",
                                        conversionJobService.get(id, user.getId()).status()));
        String download = conversionJobService.get(id, user.getId()).result().getDownloadUrl();
        var record =
                conversionRepository
                        .findByFileId(download.substring(download.lastIndexOf('/') + 1))
                        .orElseThrow();
        mvc.perform(
                        delete("/api/user/history/" + record.getId())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void longUploadNamesKeepTheirExtensionForBackgroundConversion() throws Exception {
        byte[] bytes;
        try (var pdf = new PDDocument();
                var output = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(output);
            bytes = output.toByteArray();
        }
        var accepted =
                submitJob(
                        "file-compress",
                        UUID.randomUUID().toString(),
                        null,
                        "a".repeat(250) + ".pdf",
                        bytes);
        Assertions.assertTrue(accepted.path("originalFileName").asText().endsWith(".pdf"));
        conversionJobService.dispatch();
        var completed = awaitJob(accepted.path("jobId").asText(), "succeeded");
        Assertions.assertTrue(completed.path("result").path("fileName").asText().endsWith(".pdf"));
    }
}
