package com.converter;

import static org.junit.jupiter.api.Assertions.*;

import com.converter.conversion.ConversionSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ConfigurationTest {
    @Test
    void yamlSuppliesConversionDefaults() {
        var settings = TestSettings.settings();
        assertTrue(settings.getMaxConcurrent() > 0);
        assertTrue(settings.getNativeDiagnosticBytes() > 0);
        assertTrue(settings.getJpegQuality() > 0);
        assertNotNull(settings.getOfficeBinary());
    }

    @Test
    void conversionOutputAndResourceSettingsCanBeOverridden() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "conversion.jpeg-quality", "61",
                                "conversion.gif-duration-seconds", "12",
                                "conversion.native-threads", "3",
                                "conversion.office-binary", "/custom/soffice"));
        var settings =
                new Binder(source)
                        .bind("conversion", Bindable.of(ConversionSettings.class))
                        .orElseThrow(IllegalStateException::new);
        assertEquals(61, settings.getJpegQuality());
        assertEquals(12, settings.getGifDurationSeconds());
        assertEquals(3, settings.getNativeThreads());
        assertEquals("/custom/soffice", settings.getOfficeBinary());
    }

    @Test
    void devProfileProvidesLocalSettingsAndValidSigningKey() throws Exception {
        var environment = new StandardEnvironment();
        // Spring resolves the dev variable catalog through placeholders in the base configuration.
        for (String resource : new String[] {"application-dev.yml", "application.yml"}) {
            for (var source :
                    new YamlPropertySourceLoader()
                            .load(resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addLast(source);
            }
        }
        assertTrue(environment.getRequiredProperty("jwt.secret").length() >= 64);
        assertNotNull(environment.getProperty("spring.mail.host"));
        assertNotNull(environment.getProperty("file.output-dir"));
    }
}
