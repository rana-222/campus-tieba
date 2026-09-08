package com.tieba.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NoticeRepository {
    private final JdbcTemplate jdbc;

    public NoticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SiteNotice find() {
        List<SiteNotice> rows = jdbc.query(
                "SELECT title, content FROM site_notice WHERE id = 1",
                (rs, i) -> new SiteNotice(rs.getString("title"), rs.getString("content")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void save(String title, String content) {
        int updated = jdbc.update("UPDATE site_notice SET title = ?, content = ? WHERE id = 1", title, content);
        if (updated == 0) {
            jdbc.update("INSERT INTO site_notice (id, title, content) VALUES (1, ?, ?)", title, content);
        }
    }

    public record SiteNotice(String title, String content) {}
}
