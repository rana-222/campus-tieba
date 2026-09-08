package com.tieba.repository;

import com.tieba.model.Post;
import com.tieba.model.SearchResult;
import com.tieba.util.SearchUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Repository
public class PostRepository {
    private final JdbcTemplate jdbc;

    public PostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT p.id, p.board_name, p.user_id, u.username, u.avatar, p.created_at,
                   p.likes, p.favorites, p.views, p.title, p.content, p.image_url, p.video_url
            FROM posts p JOIN users u ON u.id = p.user_id
            """;

    public List<Post> findAll(String board) {
        String sql = SELECT + (board == null || board.isBlank()
                ? " ORDER BY p.created_at DESC"
                : " WHERE p.board_name = ? ORDER BY p.created_at DESC");
        return board == null || board.isBlank()
                ? jdbc.query(sql, this::map)
                : jdbc.query(sql, this::map, board);
    }

    public List<Post> findRecommended() {
        return jdbc.query(SELECT + " WHERE p.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) ORDER BY p.likes DESC, p.created_at DESC", this::map);
    }

    public List<Post> findRecommended(String board) {
        return jdbc.query(SELECT + " WHERE p.board_name = ? AND p.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) ORDER BY p.likes DESC, p.created_at DESC", this::map, board);
    }

    public SearchResult<Post> search(String query, int page, int size) {
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
            where.append("(p.title LIKE ? OR p.content LIKE ? OR u.username LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        List<Post> all = jdbc.query(SELECT + where + " ORDER BY p.created_at DESC", this::map, args.toArray());
        List<Post> scored = new ArrayList<>(all);
        scored.sort(Comparator
                .comparingInt((Post p) -> matchedTokens(p, tokens))
                .thenComparingInt(p -> score(p, tokens))
                .reversed());

        int from = (page - 1) * size;
        long total = scored.size();
        List<Post> items = scored.stream().skip(from).limit(size).toList();
        return new SearchResult<>(items, total, page, size, from + items.size() < total);
    }

    public Post findById(long id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", rs -> rs.next() ? map(rs, 0) : null, id);
    }

    public List<Post> findByUserId(long userId) {
        return jdbc.query(SELECT + " WHERE p.user_id = ? ORDER BY p.created_at DESC", this::map, userId);
    }

    public List<Post> findFavoritesByUserId(long userId) {
        return jdbc.query(SELECT + """
                JOIN user_favorites f ON f.post_id = p.id
                WHERE f.user_id = ? ORDER BY f.created_at DESC
                """, this::map, userId);
    }

    public List<Post> findHistoryByUserId(long userId) {
        return jdbc.query(SELECT + """
                JOIN browsing_history h ON h.post_id = p.id
                WHERE h.user_id = ? ORDER BY h.viewed_at DESC
                """, this::map, userId);
    }

    public List<Map<String, Object>> replies(long postId, long userId) {
        return jdbc.queryForList("""
                  SELECT r.id, r.post_id AS postId, r.parent_id AS parentId, r.user_id AS userId,
                      r.content, r.created_at, r.likes, u.username, u.avatar AS avatar,
                      EXISTS(SELECT 1 FROM reply_likes rl WHERE rl.reply_id = r.id AND rl.user_id = ?) AS liked
                FROM replies r JOIN users u ON u.id = r.user_id
                WHERE r.post_id = ? ORDER BY r.created_at
                """, userId, postId);
    }

    public void reply(long postId, long userId, Long parentId, String content) {
        Long rootId = parentId;
        if (parentId != null) {
            rootId = jdbc.queryForObject("SELECT COALESCE(parent_id, id) FROM replies WHERE id = ? AND post_id = ?", Long.class, parentId, postId);
            if (rootId == null) throw new IllegalArgumentException("回复目标不存在");
        }
        jdbc.update("INSERT INTO replies(post_id, user_id, parent_id, content) VALUES (?, ?, ?, ?)", postId, userId, rootId, content);
    }

    public Map<String, Object> toggleReplyLike(long userId, long replyId) {
        boolean liked = jdbc.queryForObject("SELECT COUNT(*) FROM reply_likes WHERE user_id = ? AND reply_id = ?", Integer.class, userId, replyId) > 0;
        if (liked) {
            jdbc.update("DELETE FROM reply_likes WHERE user_id = ? AND reply_id = ?", userId, replyId);
            jdbc.update("UPDATE replies SET likes = CASE WHEN likes > 0 THEN likes - 1 ELSE 0 END WHERE id = ?", replyId);
        } else {
            jdbc.update("INSERT IGNORE INTO reply_likes(user_id, reply_id) VALUES (?, ?)", userId, replyId);
            jdbc.update("UPDATE replies SET likes = likes + 1 WHERE id = ?", replyId);
        }
        int likes = jdbc.queryForObject("SELECT likes FROM replies WHERE id = ?", Integer.class, replyId);
        return Map.of("liked", !liked, "likes", likes);
    }

    public boolean replyBelongsToPost(long replyId, long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM replies WHERE id = ? AND post_id = ?", Integer.class, replyId, postId) > 0;
    }

    /**
     * 作者删除自己的回复：级联清理 reply_likes（自身 + 所有子回复的）和所有子回复，
     * 再删回复本体。当前 reply() 设计中子回复 parent_id 恒指向根回复，
     * 故 WHERE parent_id = ? 能覆盖被删回复下的全部二级回复。
     */
    public boolean deleteReply(long userId, long replyId) {
        jdbc.update("DELETE FROM reply_likes WHERE reply_id IN (SELECT id FROM replies WHERE parent_id = ?)", replyId);
        jdbc.update("DELETE FROM reply_likes WHERE reply_id = ?", replyId);
        jdbc.update("DELETE FROM replies WHERE parent_id = ?", replyId);
        return jdbc.update("DELETE FROM replies WHERE id = ? AND user_id = ?", replyId, userId) > 0;
    }

    /** 管理员/版主删除任意回复：同样的级联清理，不校验作者。 */
    public boolean adminDeleteReply(long replyId) {
        jdbc.update("DELETE FROM reply_likes WHERE reply_id IN (SELECT id FROM replies WHERE parent_id = ?)", replyId);
        jdbc.update("DELETE FROM reply_likes WHERE reply_id = ?", replyId);
        jdbc.update("DELETE FROM replies WHERE parent_id = ?", replyId);
        return jdbc.update("DELETE FROM replies WHERE id = ?", replyId) > 0;
    }

    public boolean isReplyLiked(long userId, long replyId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reply_likes WHERE user_id = ? AND reply_id = ?", Integer.class, userId, replyId) > 0;
    }

    public Map<String, Object> toggleLike(long userId, long postId) {
        boolean liked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_likes WHERE user_id = ? AND post_id = ?",
                Integer.class, userId, postId) > 0;
        if (liked) {
            jdbc.update("DELETE FROM user_likes WHERE user_id = ? AND post_id = ?", userId, postId);
            jdbc.update("UPDATE posts SET likes = CASE WHEN likes > 0 THEN likes - 1 ELSE 0 END WHERE id = ?", postId);
        } else {
            jdbc.update("INSERT IGNORE INTO user_likes(user_id, post_id) VALUES (?, ?)", userId, postId);
            jdbc.update("UPDATE posts SET likes = likes + 1 WHERE id = ?", postId);
        }
        int likes = jdbc.queryForObject("SELECT likes FROM posts WHERE id = ?", Integer.class, postId);
        return Map.of("liked", !liked, "likes", likes);
    }

    public Map<String, Object> toggleFavorite(long userId, long postId, boolean on) {
        if (on) {
            int inserted = jdbc.update("INSERT IGNORE INTO user_favorites(user_id, post_id) VALUES (?, ?)", userId, postId);
            if (inserted > 0) {
                jdbc.update("UPDATE posts SET favorites = favorites + 1 WHERE id = ?", postId);
            }
        } else {
            int deleted = jdbc.update("DELETE FROM user_favorites WHERE user_id = ? AND post_id = ?", userId, postId);
            if (deleted > 0) {
                jdbc.update("UPDATE posts SET favorites = CASE WHEN favorites > 0 THEN favorites - 1 ELSE 0 END WHERE id = ?", postId);
            }
        }
        int favorites = jdbc.queryForObject("SELECT favorites FROM posts WHERE id = ?", Integer.class, postId);
        return Map.of("favorited", on, "favorites", favorites);
    }

    /**
     * 记录浏览（按用户+帖子去重）：已存在记录时仅刷新 viewed_at；
     * 首次浏览才插入记录并让浏览量 +1，避免重复点进帖子反复累计浏览量。
     */
    public void history(long userId, long postId) {
        int updated = jdbc.update(
                "UPDATE browsing_history SET viewed_at = CURRENT_TIMESTAMP WHERE user_id = ? AND post_id = ?",
                userId, postId);
        if (updated == 0) {
            int inserted = jdbc.update(
                    "INSERT IGNORE INTO browsing_history(user_id, post_id) VALUES (?, ?)", userId, postId);
            if (inserted > 0) {
                jdbc.update("UPDATE posts SET views = views + 1 WHERE id = ?", postId);
            }
        }
    }

    public long insert(long userId, String board, String title, String content, String imageUrl, String videoUrl) {
        jdbc.update("""
                INSERT INTO posts(board_name, user_id, title, content, image_url, video_url)
                VALUES (?, ?, ?, ?, ?, ?)
                """, board, userId, title, content,
                imageUrl == null ? "" : imageUrl,
                videoUrl == null ? "" : videoUrl);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id != null ? id : 0;
    }

    public boolean update(long id, long userId, String board, String title, String content, String imageUrl, String videoUrl) {
        return jdbc.update("""
                UPDATE posts SET board_name = ?, title = ?, content = ?, image_url = ?, video_url = ?
                WHERE id = ? AND user_id = ?
                """, board, title, content,
                imageUrl == null ? "" : imageUrl,
                videoUrl == null ? "" : videoUrl,
                id, userId) > 0;
    }

    public boolean delete(long id, long userId) {
        jdbc.update("DELETE FROM user_likes WHERE post_id = ?", id);
        jdbc.update("DELETE FROM user_favorites WHERE post_id = ?", id);
        jdbc.update("DELETE FROM browsing_history WHERE post_id = ?", id);
        jdbc.update("DELETE FROM reply_likes WHERE reply_id IN (SELECT id FROM replies WHERE post_id = ?)", id);
        jdbc.update("DELETE FROM replies WHERE post_id = ?", id);
        return jdbc.update("DELETE FROM posts WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    public boolean adminUpdate(long id, String board, String title, String content, String imageUrl, String videoUrl) {
        return jdbc.update("""
                UPDATE posts SET board_name = ?, title = ?, content = ?, image_url = ?, video_url = ? WHERE id = ?
                """, board, title, content, imageUrl == null ? "" : imageUrl, videoUrl == null ? "" : videoUrl, id) > 0;
    }

    public boolean adminDelete(long id) {
        jdbc.update("DELETE FROM user_likes WHERE post_id = ?", id);
        jdbc.update("DELETE FROM user_favorites WHERE post_id = ?", id);
        jdbc.update("DELETE FROM browsing_history WHERE post_id = ?", id);
        jdbc.update("DELETE FROM reply_likes WHERE reply_id IN (SELECT id FROM replies WHERE post_id = ?)", id);
        jdbc.update("DELETE FROM replies WHERE post_id = ?", id);
        return jdbc.update("DELETE FROM posts WHERE id = ?", id) > 0;
    }

    public boolean isLiked(long userId, long postId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_likes WHERE user_id = ? AND post_id = ?",
                Integer.class, userId, postId) > 0;
    }

    public boolean isFavorited(long userId, long postId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_favorites WHERE user_id = ? AND post_id = ?",
                Integer.class, userId, postId) > 0;
    }

    private int matchedTokens(Post post, List<String> tokens) {
        String title = SearchUtil.lower(post.title());
        String username = SearchUtil.lower(post.username());
        String content = SearchUtil.lower(post.content());
        int count = 0;
        for (String token : tokens) {
            if (title.contains(token) || username.contains(token) || content.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private int score(Post post, List<String> tokens) {
        String title = SearchUtil.lower(post.title());
        String username = SearchUtil.lower(post.username());
        String content = SearchUtil.lower(post.content());
        int total = 0;
        for (String token : tokens) {
            int best = 0;
            if (title.equals(token)) {
                best = 100;
            } else if (title.contains(token)) {
                best = 80;
            }
            if (username.equals(token)) {
                best = Math.max(best, 60);
            } else if (username.contains(token)) {
                best = Math.max(best, 40);
            }
            if (content.contains(token)) {
                best = Math.max(best, 20);
            }
            total += best;
        }
        return total;
    }

    private Post map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String createdAt = rs.getString("created_at");
        return new Post(
                rs.getLong("id"),
                rs.getString("board_name"),
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("avatar"),
                createdAt,
                rs.getInt("likes"),
                rs.getInt("favorites"),
                rs.getInt("views"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("image_url"),
                rs.getString("video_url")
        );
    }
}
