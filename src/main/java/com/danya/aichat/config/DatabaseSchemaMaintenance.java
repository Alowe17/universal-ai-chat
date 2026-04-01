package com.danya.aichat.config;

import java.sql.Connection;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseSchemaMaintenance {

    private final DataSource dataSource;

    @Bean
    public ApplicationRunner ensureChatSchema() {
        return arguments -> {
            String productName;
            try (Connection connection = dataSource.getConnection()) {
                productName = connection.getMetaData().getDatabaseProductName();
            }
            if (productName == null) {
                return;
            }

            String normalizedProductName = productName.toLowerCase();
            if (!normalizedProductName.contains("mysql") && !normalizedProductName.contains("mariadb")) {
                return;
            }

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            try {
                jdbcTemplate.execute("ALTER TABLE chats ADD COLUMN sort_order BIGINT NOT NULL DEFAULT 0");
            } catch (Exception exception) {
                log.debug("Skipping chats sort_order add-column migration: {}", exception.getMessage());
            }

            try {
                jdbcTemplate.execute("ALTER TABLE chats MODIFY sort_order BIGINT NOT NULL");
            } catch (Exception exception) {
                log.debug("Skipping chats sort_order modify migration: {}", exception.getMessage());
            }

            try {
                jdbcTemplate.execute("UPDATE chats SET sort_order = id WHERE sort_order IS NULL OR sort_order = 0");
            } catch (Exception exception) {
                log.debug("Skipping chats sort_order backfill migration: {}", exception.getMessage());
            }

            try {
                jdbcTemplate.execute("ALTER TABLE chat_documents MODIFY extracted_text LONGTEXT NOT NULL");
            } catch (Exception exception) {
                log.debug("Skipping chat_documents extracted_text migration: {}", exception.getMessage());
            }

            try {
                jdbcTemplate.execute("ALTER TABLE chat_messages MODIFY content LONGTEXT NOT NULL");
            } catch (Exception exception) {
                log.debug("Skipping chat_messages content migration: {}", exception.getMessage());
            }
        };
    }
}
