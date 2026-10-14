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
    "spring.mail.username=", "spring.mail.password=",
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
    @Test void healthIsPublic() throws Exception { mvc.perform(get("/actuator/health")).andExpect(status().isOk()); }
    @Test void profileRequiresAuthentication() throws Exception { mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()); }
    @Test void analyticsRequiresAdmin() throws Exception { mvc.perform(get("/api/analytics/stats")).andExpect(status().isUnauthorized()); }
    @Test void downloadsReportMissingFiles() throws Exception { mvc.perform(get("/api/download/doesnotexist")).andExpect(status().isNotFound()); }
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
