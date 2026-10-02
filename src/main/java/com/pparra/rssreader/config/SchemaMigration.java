package com.pparra.rssreader.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class SchemaMigration {

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("article", "read_at", "TIMESTAMP");
        addColumnIfMissing("article", "content_version", "INTEGER");
        addColumnIfMissing("article", "content_failure_reason", "TEXT");
        addColumnIfMissing("article", "feed_content_html", "TEXT");
        addColumnIfMissing("article", "image_url", "TEXT");
    }

    private void addColumnIfMissing(String table, String column, String type) {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        boolean present = columns.stream().anyMatch(row -> column.equalsIgnoreCase((String) row.get("name")));
        if (!present) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }
}
