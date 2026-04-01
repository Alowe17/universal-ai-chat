package com.danya.aichat.service;

import com.danya.aichat.config.OllamaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final ObjectMapper objectMapper;
    private final OllamaProperties ollamaProperties;

    public void streamChat(List<OllamaMessage> messages, Consumer<String> onChunk) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(ollamaProperties.getConnectTimeout())
                .build();

        Map<String, Object> payload = Map.of(
                "model", ollamaProperties.getModel(),
                "stream", true,
                "messages", messages
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl() + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(ollamaProperties.getRequestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(writePayload(payload)))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Ollama returned HTTP " + response.statusCode() + ": " + readAll(response.body()));
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }

                    JsonNode chunk = objectMapper.readTree(line);
                    if (chunk.hasNonNull("error")) {
                        throw new IllegalStateException(chunk.get("error").asText());
                    }

                    String delta = chunk.path("message").path("content").asText("");
                    if (!delta.isEmpty()) {
                        onChunk.accept(delta);
                    }

                    if (chunk.path("done").asBoolean(false)) {
                        break;
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to communicate with Ollama", exception);
        }
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize Ollama request", exception);
        }
    }

    private String readAll(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String normalizeBaseUrl() {
        String baseUrl = ollamaProperties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    public record OllamaMessage(String role, String content) {
    }
}
