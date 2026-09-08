package com.tieba.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class DatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbc;
    private final UploadPathConfig uploadPath;

    public DatabaseInitializer(JdbcTemplate jdbc, UploadPathConfig uploadPath) {
        this.jdbc = jdbc;
        this.uploadPath = uploadPath;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadPath.uploadDir());
        if (!tableExists("users")) {
            runScript("database/schema.sql");
        }
        addColumnIfMissing("users", "background_url", "VARCHAR(500) DEFAULT NULL");
        addColumnIfMissing("users", "banned", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumnIfMissing("replies", "parent_id", "BIGINT DEFAULT NULL");
        addColumnIfMissing("replies", "likes", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("boards", "parent_id", "BIGINT DEFAULT NULL");
        expandUserTypeEnum();
        createBoardModeratorsTable();
        createReplyLikesTable();
        createSiteNoticeTable();
        dropForeignKeys();
        dropColumnIfExists("posts", "shares");
        dedupeBrowsingHistory();
        if (postCount() < 20) runScript("database/seed.sql");
    }

    private boolean tableExists(String name) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
            Integer.class, name);
        return count != null && count > 0;
    }

    private void runScript(String path) throws IOException {
        String sql = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
    }

    private int postCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM posts", Integer.class);
        return count == null ? 0 : count;
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void dropColumnIfExists(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        if (count != null && count > 0) {
            log.info("删除列 {}.{}（转发功能已移除）", table, column);
            jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        }
    }

    /**
     * 浏览记录按（用户, 帖子）去重：删除历史重复行（保留最新一条），补建唯一索引，
     * 并把 posts.views 重算为去重后的浏览人数，修复此前重复点进帖子导致的浏览量虚高。
     */
    private void dedupeBrowsingHistory() {
        jdbc.update("""
                DELETE h1 FROM browsing_history h1
                JOIN browsing_history h2
                  ON h1.user_id = h2.user_id AND h1.post_id = h2.post_id AND h1.id < h2.id
                """);
        Integer index = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'browsing_history'
                  AND index_name = 'uk_history_user_post'
                """, Integer.class);
        if (index != null && index == 0) {
            jdbc.execute("ALTER TABLE browsing_history ADD UNIQUE KEY uk_history_user_post (user_id, post_id)");
        }
        jdbc.update("UPDATE posts p SET views = (SELECT COUNT(*) FROM browsing_history h WHERE h.post_id = p.id)");
    }

    private void createReplyLikesTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS reply_likes (
                    user_id BIGINT NOT NULL, reply_id BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (user_id, reply_id),
                    INDEX idx_reply_likes_reply(reply_id)
                )
                """);
    }

    /** 校园公告单行表（id=1）：首次启动播种默认文案，管理员可在设置中编辑。 */
    private void createSiteNoticeTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS site_notice (
                    id TINYINT PRIMARY KEY,
                    title VARCHAR(200) NOT NULL,
                    content VARCHAR(1000) NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);
        jdbc.update("INSERT IGNORE INTO site_notice (id, title, content) VALUES (1, '校园公告', '本周五晚 8 点，图书馆将进行网络维护。')");
    }

    private void expandUserTypeEnum() {
        jdbc.execute("ALTER TABLE users MODIFY COLUMN user_type ENUM('USER','ADMIN','MODERATOR') NOT NULL DEFAULT 'USER'");
    }

    private void createBoardModeratorsTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS board_moderators (
                    user_id BIGINT NOT NULL, board_id BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (user_id, board_id),
                    INDEX idx_board_moderators_board(board_id)
                )
                """);
    }

    private void dropForeignKeys() {
        List<String[]> fks = jdbc.query("""
                SELECT table_name, constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'
                """, (rs, i) -> new String[]{ rs.getString("TABLE_NAME"), rs.getString("CONSTRAINT_NAME") });
        for (String[] fk : fks) {
            try {
                jdbc.execute("ALTER TABLE `" + fk[0] + "` DROP FOREIGN KEY `" + fk[1] + "`");
            } catch (Exception e) {
                log.warn("删除外键 {}.{} 失败：{}", fk[0], fk[1], e.getMessage());
            }
        }
    }
}
