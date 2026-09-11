package com.demo.cs.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import org.springframework.http.CacheControl;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("*").allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path upload = Path.of(props.uploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(upload);
        } catch (Exception ignored) {
        }
        registry.addResourceHandler("/files/**").addResourceLocations(upload.toUri().toString());

        // Font files carry a content hash in their name, so they are safe to cache indefinitely.
        registry.addResourceHandler("/fonts/noto-sans-sc/**")
                .addResourceLocations("classpath:/static/fonts/noto-sans-sc/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // Markup, styles and scripts have stable names: force revalidation so a redeploy
        // is never masked by a stale copy in the browser.
        registry.addResourceHandler("/*.html", "/*.css", "/js/**", "/fonts/*.css")
                .addResourceLocations("classpath:/static/", "classpath:/static/js/", "classpath:/static/fonts/")
                .setCacheControl(CacheControl.noCache().mustRevalidate());
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    PromptLoader promptLoader() {
        return new PromptLoader();
    }

    public static class PromptLoader {
        public String load(String classpathRelative) {
            try {
                var res = new ClassPathResource("prompts/" + classpathRelative);
                return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8)
                        .replaceAll("(?m)^<!--.*?-->\\s*", "")
                        .trim();
            } catch (Exception e) {
                return "";
            }
        }
    }
}
