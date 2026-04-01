package com.danya.aichat;

import com.danya.aichat.config.OllamaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(OllamaProperties.class)
public class UniversalAiChatApplication {

	public static void main(String[] args) {
		SpringApplication.run(UniversalAiChatApplication.class, args);
	}
}