package com.axion11.visualops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed.origins:http://localhost:5173}")
    private String allowedOriginsRaw;

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        // Always include imagemx.online plus any origins from the environment variable
        List<String> origins = Stream.concat(
                Stream.of(
                    "https://imagemx.online",
                    "https://www.imagemx.online",
                    "http://localhost:1420",
                    "http://localhost:3000",
                    "http://localhost:3001",
                    "http://localhost:5173",
                    // Packaged Tauri desktop builds load the frontend from a webview-internal
                    // origin, not http://localhost:* — origin varies by OS/Tauri version.
                    "tauri://localhost",
                    "https://tauri.localhost",
                    "http://tauri.localhost"
                ),
                Arrays.stream(allowedOriginsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
        ).distinct().collect(Collectors.toList());

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
