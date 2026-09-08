package com.tieba.repository;

import com.tieba.model.SearchResult;
import com.tieba.model.User;
import com.tieba.util.SearchUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public User findByAccount(String account) {
        return jdbc.query("""
                SELECT id, account, username, avatar, background_url, user_type, banned FROM users WHERE account = ?
                """, rs -> rs.next()
                ? map(rs)
                : null, account);
    }

    public User findById(long id) {
        return jdbc.query("""
                SELECT id, account, username, avatar, background_url, user_type, banned FROM users WHERE id = ?
                """, rs -> rs.next()
                ? map(rs)
                : null, id);
    }

    public SearchResult<User> search(String query, int page, int size) {
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), 100);
        List<String> tokens = SearchUtil.tokenize(query);
        if (tokens.isEmpty()) {
            return new SearchResult<>(List.of(), 0, page, size, false);
        }

        StringBuilder where = new StringBuilder(" WHERE ");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) where.append(" OR ");
            String like = "%" + tokens.get(i) + "%";
            where.append("(username LIKE ? OR account LIKE ?)");
            args.add(like);
            args.add(like);
        }

        String sql = "SELECT id, account, username, avatar, background_url, user_type, banned FROM users"
                + where + " ORDER BY id";
        List<User> all = jdbc.query(sql, (rs, row) -> map(rs), args.toArray());
        List<User> scored = new ArrayList<>(all);
        scored.sort(Comparator
                .comparingInt((User u) -> matchedTokens(u, tokens))
                .thenComparingInt(u -> score(u, tokens))
                .reversed());

        int from = (page - 1) * size;
        long total = scored.size();
        List<User> items = scored.stream().skip(from).limit(size).toList();
        return new SearchResult<>(items, total, page, size, from + items.size() < total);
    }

    public String password(String account) {
        return jdbc.query("SELECT password FROM users WHERE account = ?", rs -> rs.next() ? rs.getString(1) : null, account);
    }

    public int updateAvatar(long userId, String avatar) {
        return jdbc.update("UPDATE users SET avatar = ? WHERE id = ?", avatar, userId);
    }

    public int updateProfile(long userId, String account, String username, String password, String backgroundUrl) {
        return jdbc.update("""
                UPDATE users SET account = ?, username = ?, password = COALESCE(?, password),
                    background_url = COALESCE(?, background_url) WHERE id = ?
                """, account, username, password, backgroundUrl, userId);
    }

    public int updateBackground(long userId, String backgroundUrl) {
        return jdbc.update("UPDATE users SET background_url = ? WHERE id = ?", backgroundUrl, userId);
    }

    public int setBanned(long userId, boolean banned) {
        return jdbc.update("UPDATE users SET banned = ? WHERE id = ? AND user_type <> 'ADMIN'", banned, userId);
    }

    /**
     * 逻辑外键模式下的用户级联删除：
     * 顺序遵循「子表 -> 父表」「依赖被删对象的关系 -> 被删对象本身」原则，
     * 避免留下孤儿行。所有关联表均无 DB 级外键，由应用层负责清理。
     */
    public boolean delete(long userId) {
        // 1. 用户对他人回复的点赞
        jdbc.update("DELETE FROM reply_likes WHERE user_id = ?", userId);
        // 2. 用户自己发布的回复上的他人点赞，再删用户发布的回复
        jdbc.update("DELETE FROM reply_likes WHERE reply_id IN (SELECT id FROM replies WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM replies WHERE user_id = ?", userId);
        // 3. 用户发布帖子上的所有附属数据，再删帖子本体
        jdbc.update("DELETE FROM reply_likes WHERE reply_id IN (SELECT id FROM replies WHERE post_id IN (SELECT id FROM posts WHERE user_id = ?))", userId);
        jdbc.update("DELETE FROM replies WHERE post_id IN (SELECT id FROM posts WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM user_likes WHERE post_id IN (SELECT id FROM posts WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM user_favorites WHERE post_id IN (SELECT id FROM posts WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM browsing_history WHERE post_id IN (SELECT id FROM posts WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM posts WHERE user_id = ?", userId);
        // 4. 用户对他人的点赞 / 收藏 / 浏览 / 关注
        jdbc.update("DELETE FROM user_likes WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_favorites WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM browsing_history WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_follows WHERE user_id = ?", userId);
        // 5. 版主身份
        jdbc.update("DELETE FROM board_moderators WHERE user_id = ?", userId);
        // 6. 用户本体
        return jdbc.update("DELETE FROM users WHERE id = ?", userId) > 0;
    }

    public int setUserType(long userId, String userType) {
        return jdbc.update("UPDATE users SET user_type = ? WHERE id = ? AND user_type <> 'ADMIN'", userType, userId);
    }

    public int insert(String account, String password, String username) {
        return jdbc.update("INSERT INTO users(account, password, username, user_type) VALUES (?, ?, ?, 'USER')",
                account, password, username);
    }

    public Map<String, Object> stats(long userId) {
        int posts = jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE user_id = ?", Integer.class, userId);
        // 获赞 = 帖子收到的赞 + 自己评论收到的赞
        int postLikes = jdbc.queryForObject(
                "SELECT COALESCE(SUM(likes), 0) FROM posts WHERE user_id = ?", Integer.class, userId);
        int replyLikes = jdbc.queryForObject(
                "SELECT COALESCE(SUM(likes), 0) FROM replies WHERE user_id = ?", Integer.class, userId);
        int favorites = jdbc.queryForObject("SELECT COUNT(*) FROM user_favorites WHERE user_id = ?", Integer.class, userId);
        int follows = jdbc.queryForObject("SELECT COUNT(*) FROM user_follows WHERE user_id = ?", Integer.class, userId);
        return Map.of("posts", posts, "likes", postLikes + replyLikes, "favorites", favorites, "follows", follows);
    }

    public Map<String, List<Map<String, Object>>> notifications(long userId) {
        List<Map<String, Object>> likes = jdbc.queryForList("""
                SELECT 'like' AS type, l.created_at AS createdAt, actor.username AS sourceName,
                    actor.avatar AS sourceAvatar, p.id AS postId, p.title AS postTitle,
                    '赞了你的帖子' AS content
                FROM user_likes l
                JOIN posts p ON p.id = l.post_id
                JOIN users actor ON actor.id = l.user_id
                WHERE p.user_id = ? AND l.user_id <> ?
                UNION ALL
                SELECT 'like' AS type, l.created_at AS createdAt, actor.username AS sourceName,
                    actor.avatar AS sourceAvatar, p.id AS postId, p.title AS postTitle,
                    '赞了你的评论' AS content
                FROM reply_likes l
                JOIN replies r ON r.id = l.reply_id
                JOIN posts p ON p.id = r.post_id
                JOIN users actor ON actor.id = l.user_id
                WHERE r.user_id = ? AND l.user_id <> ?
                ORDER BY createdAt DESC
                """, userId, userId, userId, userId);
        List<Map<String, Object>> replies = jdbc.queryForList("""
                SELECT 'reply' AS type, r.created_at AS createdAt, actor.username AS sourceName,
                    actor.avatar AS sourceAvatar, r.content, p.id AS postId, p.title AS postTitle
                FROM replies r
                JOIN posts p ON p.id = r.post_id
                JOIN users actor ON actor.id = r.user_id
                LEFT JOIN replies parent ON parent.id = r.parent_id
                WHERE (p.user_id = ? OR parent.user_id = ?) AND r.user_id <> ?
                ORDER BY r.created_at DESC
                """, userId, userId, userId);
        return Map.of("likes", likes, "replies", replies);
    }

    private int matchedTokens(User user, List<String> tokens) {
        String username = SearchUtil.lower(user.username());
        String account = SearchUtil.lower(user.account());
        int count = 0;
        for (String token : tokens) {
            if (username.contains(token) || account.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private int score(User user, List<String> tokens) {
        String username = SearchUtil.lower(user.username());
        String account = SearchUtil.lower(user.account());
        int total = 0;
        for (String token : tokens) {
            int best = 0;
            if (username.equals(token)) {
                best = 100;
            } else if (username.contains(token)) {
                best = 80;
            }
            if (account.equals(token)) {
                best = Math.max(best, 90);
            } else if (account.contains(token)) {
                best = Math.max(best, 50);
            }
            total += best;
        }
        return total;
    }

    private User map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new User(rs.getLong("id"), rs.getString("account"), rs.getString("username"),
                rs.getString("avatar"), rs.getString("background_url"), rs.getString("user_type"),
                rs.getBoolean("banned"));
    }
}
