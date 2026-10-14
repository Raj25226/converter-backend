package com.converter;

import com.converter.conversion.ConversionSettings;
import com.converter.conversion.NativeProcessRunner;
import com.converter.service.FileConversionService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.file.Path;

final class TestSettings {
    private TestSettings() { }
    static ConversionSettings settings() {
        try {
            var environment = new StandardEnvironment();
            for (var source : new YamlPropertySourceLoader().load("defaults", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
            return Binder.get(environment).bind("conversion", Bindable.of(ConversionSettings.class)).orElseThrow(IllegalStateException::new);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    static NativeProcessRunner runner() { return new NativeProcessRunner(settings()); }
    static FileConversionService service(Path directory) {
        var settings = settings();
        return new FileConversionService(directory, settings, new NativeProcessRunner(settings));
    }
}
