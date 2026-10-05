package io.renova.web;

import io.renova.core.engine.PluginRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** The Renova web API: the engine behind a REST interface for the web console. */
@SpringBootApplication
public class RenovaWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(RenovaWebApplication.class, args);
    }

    /** Ecosystem plugins and AI providers on the classpath. */
    @Bean
    PluginRegistry pluginRegistry() {
        return PluginRegistry.load();
    }
}
