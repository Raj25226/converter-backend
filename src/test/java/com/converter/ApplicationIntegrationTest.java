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
    "conversion.native-health-enabled=false", "spring.mail.username=", "spring.mail.password=",
    "file.output-dir=/tmp/converter-integration/outputs", "file.upload-dir=/tmp/converter-integration/uploads"
})
@AutoConfigureMockMvc
class ApplicationIntegrationTest {
    @Autowired MockMvc mvc;
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

}
