package me.vonyayou.geminibridge.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DataDirectory {
    @Bean
    static BeanFactoryPostProcessor prepareDataDirectory(Environment environment) {
        return factory -> {
            try {
                Files.createDirectories(Path.of(environment.getProperty("bridge.data-dir", "./data")));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create the data directory");
            }
        };
    }
}
