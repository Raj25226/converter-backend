package com.converter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.converter.service.QuotaService;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:converter;MODE=PostgreSQL;NON_KEYWORDS=DAY;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "jwt.secret=integration-test-only-secret-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "jobs.poll-interval-ms=3600000", "conversion.native-health-enabled=false", "spring.mail.username=", "spring.mail.password=",
    "file.output-dir=/tmp/converter-integration/outputs", "file.upload-dir=/tmp/converter-integration/uploads"
})
@AutoConfigureMockMvc
class ApplicationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired com.converter.service.ConversionJobService jobs;
    @Autowired com.converter.conversion.JobSettings jobSettings;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.converter.service.ConversionService conversionService;
    @Autowired com.converter.conversion.ConversionSettings settings;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("uploadPath") java.nio.file.Path uploadPath;
    @Autowired com.converter.repository.UserRepository users;
    @Autowired com.converter.util.JwtUtil jwt;
    @Autowired com.converter.repository.EmailTokenRepository emailTokens;
    @Autowired com.converter.repository.ConversionRepository conversions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoBean QuotaService quotas; // Reservation SQL needs PostgreSQL; H2 validates schema and HTTP wiring.
    @Test void missingUploadIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/convert/pdf-to-word")).andExpect(status().isBadRequest()).andExpect(header().exists("X-Request-ID"));
    }
    @Test void wrongMethodAndMediaTypeAreClientErrors() throws Exception {
        mvc.perform(get("/api/convert/pdf-to-word")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/convert/pdf-to-word").contentType("application/json").content("{}"))
            .andExpect(status().isUnsupportedMediaType());
    }
    @Test void everyConversionRouteProducesDownloadableValidatedOutput() throws Exception {
        java.nio.file.Path fixtures = java.nio.file.Files.createTempDirectory("converter-api-fixtures-");
        try {
            var runner = TestSettings.runner();
            String magick = TestSettings.settings().getImageMagickBinary();
            java.util.Map<String, java.nio.file.Path> inputs = new java.util.LinkedHashMap<>();
            var docx = fixtures.resolve("word.docx");
            try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var out = java.nio.file.Files.newOutputStream(docx)) {
                doc.createParagraph().createRun().setText("Invoice details"); doc.write(out);
            }
            inputs.put("word-to-pdf", docx);
            var xlsx = fixtures.resolve("sheet.xlsx");
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = java.nio.file.Files.newOutputStream(xlsx)) {
                book.createSheet("Sales").createRow(0).createCell(0).setCellValue("Invoice details"); book.write(out);
            }
            inputs.put("excel-to-pdf", xlsx);
            var pptx = fixtures.resolve("slides.pptx");
            try (var deck = new org.apache.poi.xslf.usermodel.XMLSlideShow(); var out = java.nio.file.Files.newOutputStream(pptx)) {
                var text = deck.createSlide().createTextBox();
                text.setAnchor(new java.awt.geom.Rectangle2D.Double(30,30,500,100)); text.setText("Invoice details"); deck.write(out);
            }
            inputs.put("ppt-to-pdf", pptx);
            var pdf = fixtures.resolve("text.pdf");
            try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
                var page = new org.apache.pdfbox.pdmodel.PDPage(); document.addPage(page);
                try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(50,700); stream.showText("Invoice details"); stream.endText();
                }
                document.save(pdf.toFile());
            }
            inputs.put("pdf-to-word",pdf); inputs.put("pdf-to-excel",pdf);
            for (String ext : java.util.List.of("jpg","png","webp","heic")) {
                runner.execute(java.util.List.of(magick,"-size","24x16","gradient:red-blue",fixtures.resolve("image."+ext).toString()),fixtures,30000,null,Long.MAX_VALUE);
            }
            inputs.put("jpg-to-png",fixtures.resolve("image.jpg")); inputs.put("jpg-to-webp",fixtures.resolve("image.jpg"));
            inputs.put("png-to-jpg",fixtures.resolve("image.png")); inputs.put("webp-to-png",fixtures.resolve("image.webp"));
            inputs.put("heic-to-jpg",fixtures.resolve("image.heic"));
            var video = fixtures.resolve("clip.mp4");
            runner.execute(java.util.List.of("ffmpeg","-v","error","-y","-f","lavfi","-i","testsrc2=size=64x64:rate=10","-f","lavfi","-i","sine=frequency=440","-t","1","-c:v","mpeg4","-c:a","aac",video.toString()),fixtures,30000,null,Long.MAX_VALUE);
            inputs.put("mp4-to-mp3",video); inputs.put("mp4-to-gif",video);
            var wav = fixtures.resolve("audio.wav");
            runner.execute(java.util.List.of("ffmpeg","-v","error","-y","-f","lavfi","-i","sine=frequency=440","-t","1",wav.toString()),fixtures,30000,null,Long.MAX_VALUE);
            inputs.put("wav-to-mp3",wav);
            var zip = fixtures.resolve("archive.zip");
            try (var archive = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
                archive.putNextEntry(new java.util.zip.ZipEntry("note.txt")); archive.write("Invoice details".getBytes()); archive.closeEntry();
            }
            inputs.put("zip-extract",zip);
            var text = fixtures.resolve("note.txt"); java.nio.file.Files.writeString(text,"Invoice details"); inputs.put("file-compress",text);
            String smokeDirectory = System.getenv("CONVERSION_SMOKE_FIXTURES");
            if (smokeDirectory != null && !smokeDirectory.isBlank()) {
                var target = java.nio.file.Path.of(smokeDirectory); java.nio.file.Files.createDirectories(target);
                try (var files = java.nio.file.Files.list(fixtures)) {
                    for (var source : files.toList()) java.nio.file.Files.copy(source, target.resolve(source.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (var input : inputs.entrySet()) {
                var response = mvc.perform(multipart("/api/convert/"+input.getKey())
                    .file(new MockMultipartFile("file",input.getValue().getFileName().toString(),"application/octet-stream",java.nio.file.Files.readAllBytes(input.getValue()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success")).andReturn();
                var data = json.readTree(response.getResponse().getContentAsString());
                if (java.util.Set.of("pdf-to-word", "pdf-to-excel", "ppt-to-pdf", "jpg-to-png", "png-to-jpg", "jpg-to-webp", "webp-to-png", "heic-to-jpg", "mp4-to-mp3", "mp4-to-gif", "wav-to-mp3").contains(input.getKey())) {
                    org.junit.jupiter.api.Assertions.assertTrue(data.path("warnings").isArray() && !data.path("warnings").isEmpty(), input.getKey());
                }
                var download = mvc.perform(get(java.net.URI.create(data.get("downloadUrl").asText()).getPath()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
                byte[] bytes = download.getResponse().getContentAsByteArray();
                String name = data.get("fileName").asText();
                org.junit.jupiter.api.Assertions.assertTrue(bytes.length>0,input.getKey());
                if (name.endsWith(".pdf")) { try (var result = org.apache.pdfbox.Loader.loadPDF(bytes)) { org.junit.jupiter.api.Assertions.assertTrue(result.getNumberOfPages()>0); } }
                else if (name.endsWith(".docx")) { try (var result = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes))) { org.junit.jupiter.api.Assertions.assertFalse(result.getParagraphs().isEmpty()); } }
                else if (name.endsWith(".xlsx")) { try (var result = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) { org.junit.jupiter.api.Assertions.assertTrue(result.getNumberOfSheets()>0); } }
                else if (name.endsWith(".mp3")) { org.junit.jupiter.api.Assertions.assertTrue(download.getResponse().getContentType().startsWith("audio/mpeg")); }
                else if (name.endsWith(".zip")) { try (var result = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) { org.junit.jupiter.api.Assertions.assertEquals("note.txt",result.getNextEntry().getName()); } }
                else if (name.endsWith(".gz")) { try (var result = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) { org.junit.jupiter.api.Assertions.assertEquals("Invoice details",new String(result.readAllBytes())); } }
                else { org.junit.jupiter.api.Assertions.assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)),input.getKey()); }
            }
        } finally {
            try (var paths = java.nio.file.Files.walk(fixtures)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            }
        }
    }
    @Test void healthIsPublic() throws Exception { mvc.perform(get("/actuator/health")).andExpect(status().isOk()); }
    @Test void profileRequiresAuthentication() throws Exception { mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()); }
    @Test void analyticsRequiresAdmin() throws Exception { mvc.perform(get("/api/analytics/stats")).andExpect(status().isUnauthorized()); }
    @Test void downloadsReportMissingFiles() throws Exception { mvc.perform(get("/api/download/" + "0".repeat(32))).andExpect(status().isNotFound()); }
    @Test void invalidPdfProducesClientError() throws Exception {
        mvc.perform(multipart("/api/convert/pdf-to-word").file(new MockMultipartFile("file", "test.pdf", "application/pdf", "invalid data".getBytes())))
            .andExpect(status().isUnprocessableEntity());
    }
    @Test void guestQuotaIsPublic() throws Exception { mvc.perform(get("/api/convert/quota")).andExpect(status().isOk()).andExpect(jsonPath("$.limit").value(2)); }
    @Test void revokedAdminRoleIsEffectiveForExistingToken() throws Exception {
        var user = new com.converter.entity.User("revoked@example.test", null, "Test", "Admin");
        user.setRole(com.converter.entity.User.Role.ADMIN); user.setEmailVerified(true);
        user = users.saveAndFlush(user);
        String token = jwt.generateToken(user);
        user.setRole(com.converter.entity.User.Role.USER); users.saveAndFlush(user);
        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
    @Test void suspendedUserCannotUseExistingToken() throws Exception {
        var user = users.saveAndFlush(new com.converter.entity.User("suspended@example.test", null, "Test", "User"));
        String token = jwt.generateToken(user);
        user.setActive(false); users.saveAndFlush(user);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void conversionCanBeDownloaded() throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "jpg", bytes);
        var response = mvc.perform(multipart("/api/convert/jpg-to-png")
            .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", bytes.toByteArray())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success")).andReturn();
        String url = json.readTree(response.getResponse().getContentAsString()).get("downloadUrl").asText();
        var download = mvc.perform(get(java.net.URI.create(url).getPath())).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(download.getResponse().getContentAsByteArray())));
    }

    @Test void passwordVersionRevokesExistingToken() throws Exception {
        var user = users.saveAndFlush(new com.converter.entity.User("version@example.test", null, "Test", "User"));
        String token = jwt.generateToken(user);
        user.setTokenVersion(user.getTokenVersion() + 1); users.saveAndFlush(user);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void deletingUserAlsoDeletesAssociatedRecords() throws Exception {
        var admin = new com.converter.entity.User("deleteadmin@example.test", null, "Test", "Admin");
        admin.setRole(com.converter.entity.User.Role.ADMIN); admin = users.saveAndFlush(admin);
        var victim = users.saveAndFlush(new com.converter.entity.User("deletevictim@example.test", null, "Test", "User"));
        var email = emailTokens.saveAndFlush(new com.converter.entity.EmailToken(victim, "deletion-test", com.converter.entity.EmailToken.TokenType.VERIFY_EMAIL, java.time.Instant.now().plusSeconds(600)));
        var record = new com.converter.entity.Conversion(); record.setUser(victim); record.setConverterType("test");
        record.setExpiresAt(java.time.Instant.now().plusSeconds(600)); record = conversions.saveAndFlush(record);
        mvc.perform(delete("/api/admin/users/" + victim.getId()).header("Authorization", "Bearer " + jwt.generateToken(admin))).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertFalse(users.existsById(victim.getId()));
        org.junit.jupiter.api.Assertions.assertFalse(emailTokens.existsById(email.getId()));
        org.junit.jupiter.api.Assertions.assertFalse(conversions.existsById(record.getId()));
    }
    private com.fasterxml.jackson.databind.JsonNode submitJob(String tool, String requestId, String token, String filename, byte[] bytes) throws Exception {
        var request = multipart("/api/jobs/" + tool).file(new MockMultipartFile("file", filename, "application/octet-stream", bytes)).param("requestId", requestId);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json.readTree(mvc.perform(request).andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
    }
    private com.fasterxml.jackson.databind.JsonNode awaitJob(String id, String expected) {
        var value = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            var body = mvc.perform(get("/api/jobs/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var data = json.readTree(body); org.junit.jupiter.api.Assertions.assertEquals(expected, data.path("status").asText()); value.set(data);
        });
        return value.get();
    }
    @Test void jobSubmissionIsIdempotentAndResultSurvivesSeparateHttpRequests() throws Exception {
        org.mockito.Mockito.clearInvocations(quotas);
        String request = java.util.UUID.randomUUID().toString();
        var job = submitJob("file-compress", request, null, "note.txt", "recoverable contents".getBytes());
        var duplicate = submitJob("file-compress", request, null, "note.txt", "recoverable contents".getBytes());
        org.junit.jupiter.api.Assertions.assertEquals(job.path("jobId"), duplicate.path("jobId"));
        org.mockito.Mockito.verify(quotas, org.mockito.Mockito.times(1)).reserve(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(2));
        mvc.perform(get("/api/jobs/request/" + request)).andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(job.path("jobId").asText()));
        jobs.dispatch(); var result = awaitJob(job.path("jobId").asText(), "succeeded");
        var download = mvc.perform(get(java.net.URI.create(result.path("result").path("downloadUrl").asText()).getPath())).andExpect(status().isOk()).andReturn();
        try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(download.getResponse().getContentAsByteArray()))) {
            org.junit.jupiter.api.Assertions.assertEquals("recoverable contents", new String(gzip.readAllBytes()));
        }
    }
    @Test void queuedJobsCanBeCancelledAndNeverProduceOutputs() throws Exception {
        var job = submitJob("file-compress", java.util.UUID.randomUUID().toString(), null, "cancel.txt", "cancel me".getBytes());
        String id = job.path("jobId").asText();
        mvc.perform(delete("/api/jobs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        jobs.dispatch(); jobs.maintain();
        mvc.perform(get("/api/jobs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled")).andExpect(jsonPath("$.result").doesNotExist());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(uploadPath.resolve("jobs/" + id + ".upload")));
    }
    @Test void ownedJobsAreNotVisibleToOtherUsersOrAnonymousClients() throws Exception {
        var owner = users.saveAndFlush(new com.converter.entity.User("job-owner@example.test", null, "Job", "Owner"));
        var other = users.saveAndFlush(new com.converter.entity.User("job-other@example.test", null, "Job", "Other"));
        String token = jwt.generateToken(owner);
        var job = submitJob("file-compress", java.util.UUID.randomUUID().toString(), token, "owned.txt", "private".getBytes());
        String path = "/api/jobs/" + job.path("jobId").asText();
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", "Bearer " + jwt.generateToken(other))).andExpect(status().isNotFound());
        mvc.perform(delete(path).header("Authorization", "Bearer " + jwt.generateToken(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs").param("tool", "file-compress").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].jobId").value(job.path("jobId").asText()));
        mvc.perform(delete(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }
    @Test void fullQueueRejectsNewRequestsButAllowsIdempotentLookup() throws Exception {
        int previous = jobSettings.getMaxPending(); jobSettings.setMaxPending(1); String id = null;
        try {
            String request = java.util.UUID.randomUUID().toString();
            var job = submitJob("file-compress", request, null, "queued.txt", "queued".getBytes()); id = job.path("jobId").asText();
            submitJob("file-compress", request, null, "queued.txt", "queued".getBytes());
            mvc.perform(multipart("/api/jobs/file-compress").file(new MockMultipartFile("file", "full.txt", "text/plain", "full".getBytes())).param("requestId", java.util.UUID.randomUUID().toString())).andExpect(status().isServiceUnavailable());
        } finally { if (id != null) mvc.perform(delete("/api/jobs/" + id)); jobSettings.setMaxPending(previous); }
    }
    @Test void jobFailuresArePersistedAsStatusInsteadOfAnUploadHttpError() throws Exception {
        var job = submitJob("pdf-to-word", java.util.UUID.randomUUID().toString(), null, "invalid.pdf", "not a pdf".getBytes());
        jobs.dispatch(); var failed = awaitJob(job.path("jobId").asText(), "failed");
        org.junit.jupiter.api.Assertions.assertTrue(failed.path("error").asText().contains("valid PDF"));
    }
    @Test void queuedJobsCanBeClaimedByAnotherWorkerInstance() throws Exception {
        var job = submitJob("file-compress", java.util.UUID.randomUUID().toString(), null, "restart.txt", "stored upload".getBytes());
        var alternate = new com.converter.service.ConversionJobService(jdbc, transactions, conversionService, settings, jobSettings, json, uploadPath);
        try { alternate.dispatch(); awaitJob(job.path("jobId").asText(), "succeeded"); }
        finally { alternate.close(); }
    }
    @Test void historyPaginationIsBoundedAndStableForIdenticalTimestamps() throws Exception {
        var user = users.saveAndFlush(new com.converter.entity.User("paged-history@example.test", null, "Page", "Test"));
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            var record = new com.converter.entity.Conversion(); record.setUser(user); record.setConverterType("test"); record.setExpiresAt(java.time.Instant.now().plusSeconds(600));
            ids.add(conversions.saveAndFlush(record).getId());
        }
        jdbc.update("UPDATE conversions SET created_at = ? WHERE user_id = ?", java.sql.Timestamp.from(java.time.Instant.now()), user.getId());
        String auth = "Bearer " + jwt.generateToken(user);
        mvc.perform(get("/api/user/history").param("size", "2").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.conversions.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.conversions[0].id").value(ids.get(2).intValue()));
        mvc.perform(get("/api/user/history").param("page", "1").param("size", "2").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.conversions.length()").value(1)).andExpect(jsonPath("$.conversions[0].id").value(ids.get(0).intValue()));
        mvc.perform(get("/api/user/history").param("size", "10000").header("Authorization", auth)).andExpect(status().isBadRequest());
    }
    @Test void exhaustedQuotaDoesNotCreateJobOrStageAnUpload() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM conversion_jobs", Long.class);
        org.mockito.Mockito.doThrow(new com.converter.exception.RateLimitException("Daily limit reached."))
                .when(quotas).reserve(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(2));
        try {
            mvc.perform(multipart("/api/jobs/file-compress").file(new MockMultipartFile("file", "quota.txt", "text/plain", "rejected".getBytes()))
                    .param("requestId", java.util.UUID.randomUUID().toString())).andExpect(status().isTooManyRequests());
            org.junit.jupiter.api.Assertions.assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM conversion_jobs", Long.class));
        } finally {
            org.mockito.Mockito.doNothing().when(quotas).reserve(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(2));
        }
    }
    @Test void deletingCompletedHistoryInvalidatesRecoveredJobDownload() throws Exception {
        var user = users.saveAndFlush(new com.converter.entity.User("removed-job-result@example.test", null, "Removed", "Result"));
        String token = jwt.generateToken(user);
        var accepted = submitJob("file-compress", java.util.UUID.randomUUID().toString(), token, "remove.txt", "remove this".getBytes());
        String id = accepted.path("jobId").asText(); jobs.dispatch();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                org.junit.jupiter.api.Assertions.assertEquals("succeeded", jobs.get(id, user.getId()).status()));
        String download = jobs.get(id, user.getId()).result().getDownloadUrl();
        var record = conversions.findByFileId(download.substring(download.lastIndexOf('/') + 1)).orElseThrow();
        mvc.perform(delete("/api/user/history/" + record.getId()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired")).andExpect(jsonPath("$.result").doesNotExist());
    }
    @Test void longUploadNamesKeepTheirExtensionForBackgroundConversion() throws Exception {
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var output = new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage()); pdf.save(output); bytes = output.toByteArray();
        }
        var accepted = submitJob("file-compress", java.util.UUID.randomUUID().toString(), null, "a".repeat(250) + ".pdf", bytes);
        org.junit.jupiter.api.Assertions.assertTrue(accepted.path("originalFileName").asText().endsWith(".pdf"));
        jobs.dispatch(); var completed = awaitJob(accepted.path("jobId").asText(), "succeeded");
        org.junit.jupiter.api.Assertions.assertTrue(completed.path("result").path("fileName").asText().endsWith(".pdf"));
    }

}
