package com.pparra.rssreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaMigrationTest {

    @TempDir Path dir;

    private JdbcTemplate oldDatabase() throws Exception {
        Path file = Files.createFile(dir.resolve("old.db"));
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
        jdbc.execute("""
                CREATE TABLE article (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, source_id INTEGER NOT NULL, url TEXT NOT NULL,
                    title TEXT NOT NULL, published_at TIMESTAMP, fetched_at TIMESTAMP NOT NULL,
                    is_read BOOLEAN NOT NULL DEFAULT 0, content_html TEXT, content_origin TEXT,
                    content_fetched_at TIMESTAMP, UNIQUE (source_id, url))""");
        jdbc.update("insert into article (source_id, url, title, fetched_at, is_read, content_html, content_origin)"
                + " values (1, 'https://a.com/1', 'Old', 1000, 1, '<p>noisy</p>', 'ORIGINAL')");
        return jdbc;
    }

    private List<String> columns(JdbcTemplate jdbc) {
        return jdbc.queryForList("PRAGMA table_info(article)").stream().map(row -> (String) row.get("name")).toList();
    }

    @Test
    void addsMissingColumnsToAnExistingDatabaseKeepingItsData() throws Exception {
        JdbcTemplate jdbc = oldDatabase();
        assertThat(columns(jdbc)).doesNotContain("read_at", "content_version");

        new SchemaMigration(jdbc).migrate();

        assertThat(columns(jdbc)).contains("read_at", "content_version");
        assertThat(jdbc.queryForObject("select title from article", String.class)).isEqualTo("Old");
        assertThat(jdbc.queryForObject("select read_at from article", Object.class)).isNull();
        assertThat(jdbc.queryForObject("select content_version from article", Object.class)).isNull();
    }

    @Test
    void runningTwiceIsHarmless() throws Exception {
        JdbcTemplate jdbc = oldDatabase();
        SchemaMigration migration = new SchemaMigration(jdbc);

        migration.migrate();
        migration.migrate();

        assertThat(columns(jdbc).stream().filter("read_at"::equals).count()).isEqualTo(1);
    }
}
