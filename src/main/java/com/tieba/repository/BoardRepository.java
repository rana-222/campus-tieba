package com.tieba.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class BoardRepository {
    private final JdbcTemplate jdbc;

    public BoardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("SELECT id, name, icon, parent_id AS parentId, hot_score FROM boards ORDER BY hot_score DESC");
    }

    public List<Map<String, Object>> followedByUser(long userId) {
        return jdbc.queryForList("""
                SELECT b.id, b.name, b.icon, b.hot_score
                FROM boards b JOIN user_follows f ON f.board_id = b.id
                WHERE f.user_id = ? ORDER BY b.hot_score DESC
                """, userId);
    }

    public boolean isFollowing(long userId, String boardName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_follows f
                JOIN boards b ON b.id = f.board_id
                WHERE f.user_id = ? AND b.name = ?
                """, Integer.class, userId, boardName);
        return count != null && count > 0;
    }

    public Map<String, Object> toggleFollow(long userId, String boardName, boolean on) {
        Long boardId = jdbc.queryForObject("SELECT id FROM boards WHERE name = ?", Long.class, boardName);
        if (boardId == null) {
            return Map.of("following", false, "message", "板块不存在");
        }
        if (on) {
            jdbc.update("INSERT IGNORE INTO user_follows(user_id, board_id) VALUES (?, ?)", userId, boardId);
        } else {
            jdbc.update("DELETE FROM user_follows WHERE user_id = ? AND board_id = ?", userId, boardId);
        }
        return Map.of("following", on);
    }

    public int create(String name, String icon, String parentName, long creatorId) {
        Long parentId = parentName == null || parentName.isBlank() ? null : jdbc.queryForObject("SELECT id FROM boards WHERE name = ?", Long.class, parentName);
        return jdbc.update("INSERT INTO boards(name, icon, parent_id, creator_id) VALUES (?, ?, ?, ?)", name, icon, parentId, creatorId);
    }

    public boolean isManagedParent(long userId, String parentName) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM board_moderators bm
                JOIN boards parent ON parent.id = bm.board_id
                WHERE bm.user_id = ? AND parent.name = ? AND parent.parent_id IS NULL
                """, Integer.class, userId, parentName) > 0;
    }

    public boolean canManageBoard(long userId, String boardName) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM board_moderators bm
                JOIN boards parent ON parent.id = bm.board_id AND parent.parent_id IS NULL
                JOIN boards child ON child.parent_id = parent.id
                WHERE bm.user_id = ? AND child.name = ?
                """, Integer.class, userId, boardName) > 0;
    }

    public boolean canManagePost(long userId, long postId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM posts p
                JOIN boards b ON b.name = p.board_name
                JOIN board_moderators bm ON bm.user_id = ?
                WHERE p.id = ? AND (bm.board_id = b.id OR bm.board_id = b.parent_id)
                """, Integer.class, userId, postId) > 0;
    }

    public boolean assignModerator(long userId, String boardName, boolean enabled) {
        Long boardId = jdbc.queryForObject("SELECT id FROM boards WHERE name = ? AND parent_id IS NULL", Long.class, boardName);
        if (boardId == null) return false;
        if (enabled) {
            jdbc.update("INSERT IGNORE INTO board_moderators(user_id, board_id) VALUES (?, ?)", userId, boardId);
        } else {
            jdbc.update("DELETE FROM board_moderators WHERE user_id = ? AND board_id = ?", userId, boardId);
        }
        return true;
    }

    public int moderatorAssignmentCount(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM board_moderators WHERE user_id = ?", Integer.class, userId);
    }

    public List<Map<String, Object>> children(String parentName) {
        return jdbc.queryForList("""
                SELECT child.id, child.name, child.icon, child.parent_id AS parentId, child.hot_score
                FROM boards child JOIN boards parent ON parent.id = child.parent_id
                WHERE parent.name = ? ORDER BY child.hot_score DESC, child.name
                """, parentName);
    }

    public int delete(String name) {
        jdbc.update("DELETE FROM reply_likes WHERE reply_id IN (SELECT id FROM replies WHERE post_id IN (SELECT id FROM posts WHERE board_name = ?))", name);
        jdbc.update("DELETE FROM replies WHERE post_id IN (SELECT id FROM posts WHERE board_name = ?)", name);
        jdbc.update("DELETE FROM user_likes WHERE post_id IN (SELECT id FROM posts WHERE board_name = ?)", name);
        jdbc.update("DELETE FROM user_favorites WHERE post_id IN (SELECT id FROM posts WHERE board_name = ?)", name);
        jdbc.update("DELETE FROM browsing_history WHERE post_id IN (SELECT id FROM posts WHERE board_name = ?)", name);
        jdbc.update("DELETE FROM posts WHERE board_name = ?", name);
        jdbc.update("DELETE FROM user_follows WHERE board_id = (SELECT id FROM boards WHERE name = ?)", name);
        jdbc.update("DELETE FROM board_moderators WHERE board_id = (SELECT id FROM boards WHERE name = ?)", name);
        return jdbc.update("DELETE FROM boards WHERE name = ?", name);
    }
}
