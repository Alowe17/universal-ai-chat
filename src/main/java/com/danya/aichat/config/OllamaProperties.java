package com.danya.aichat.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ollama")
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";
    private String model = "deepseek-r1:7b";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofMinutes(5);
}
