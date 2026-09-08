package com.tieba.controller;

import com.tieba.model.Post;
import com.tieba.repository.PostRepository;
import com.tieba.repository.BoardRepository;
import com.tieba.repository.UserRepository;
import com.tieba.service.UploadFileCleaner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private final PostRepository posts;
    private final UserRepository users;
    private final BoardRepository boards;
    private final UploadFileCleaner cleaner;

    public PostController(PostRepository posts, UserRepository users, BoardRepository boards,
                          UploadFileCleaner cleaner) {
        this.posts = posts;
        this.users = users;
        this.boards = boards;
        this.cleaner = cleaner;
    }

    @GetMapping
    public Object list(@RequestParam(required = false) String board,
                       @RequestParam(defaultValue = "false") boolean recommended) {
        if (board == null || board.isBlank()) return posts.findRecommended();
        return recommended ? posts.findRecommended(board) : posts.findAll(board);
    }

    @GetMapping("/recommended")
    public Object recommended() {
        return posts.findRecommended();
    }

    @GetMapping("/search")
    public Object search(@RequestParam String q,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "50") int size) {
        return posts.search(q, page, size);
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable long id) {
        var post = posts.findById(id);
        if (post == null) {
            return ResponseEntity.notFound().build();
        }
        return post;
    }

    @GetMapping("/{id}/replies")
    public Object replies(@PathVariable long id, @RequestParam long userId) {
        return posts.replies(id, userId);
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<?> reply(@PathVariable long id, @RequestBody ReplyRequest request) {
        var actor = users.findById(request.userId());
        if (actor == null || actor.banned()) return ResponseEntity.status(403).body(Map.of("message", "账号不可回复"));
        try {
            posts.reply(id, request.userId(), request.parentId(), request.content());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("message", "回复成功"));
    }

    @PostMapping("/{id}/replies/{replyId}/like")
    public ResponseEntity<?> replyLike(@PathVariable long id, @PathVariable long replyId, @RequestBody LikeRequest request) {
        var actor = users.findById(request.userId());
        if (actor == null || actor.banned()) return ResponseEntity.status(403).body(Map.of("message", "账号不可操作"));
        if (!posts.replyBelongsToPost(replyId, id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(posts.toggleReplyLike(request.userId(), replyId));
    }

    @DeleteMapping("/{id}/replies/{replyId}")
    public ResponseEntity<?> deleteReply(@PathVariable long id, @PathVariable long replyId, @RequestParam long userId) {
        var actor = users.findById(userId);
        if (actor == null || actor.banned()) {
            return ResponseEntity.status(403).body(Map.of("message", "账号不可操作"));
        }
        if (!posts.replyBelongsToPost(replyId, id)) return ResponseEntity.notFound().build();
        boolean ok;
        if ("ADMIN".equals(actor.userType())
                || "MODERATOR".equals(actor.userType()) && boards.canManagePost(userId, id)) {
            ok = posts.adminDeleteReply(replyId);
        } else {
            ok = posts.deleteReply(userId, replyId);
        }
        return ok ? ResponseEntity.ok(Map.of("message", "回复已删除"))
                : ResponseEntity.badRequest().body(Map.of("message", "无权删除或回复不存在"));
    }

    @PostMapping("/{id}/like")
    public ResponseEntity<?> like(@PathVariable long id, @RequestBody LikeRequest request) {
        return ResponseEntity.ok(posts.toggleLike(request.userId(), id));
    }

    @PostMapping("/{id}/favorite")
    public ResponseEntity<?> favorite(@PathVariable long id, @RequestBody FavoriteRequest request) {
        return ResponseEntity.ok(posts.toggleFavorite(request.userId(), id, request.favorite()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var actor = users.findById(request.userId());
        if (actor == null || actor.banned()) {
            return ResponseEntity.status(403).body(Map.of("message", "账号不可发帖"));
        }
        long id = posts.insert(request.userId(), request.board(), request.title(), request.content(),
                request.imageUrl(), request.videoUrl());
        return ResponseEntity.ok(Map.of("message", "发布成功", "id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody UpdateRequest request) {
        var actor = users.findById(request.userId());
        var old = posts.findById(id);
        if (actor != null && !actor.banned() && ("ADMIN".equals(actor.userType())
            || "MODERATOR".equals(actor.userType()) && boards.canManagePost(request.userId(), id))) {
            boolean ok = posts.adminUpdate(id, request.board(), request.title(), request.content(), request.imageUrl(), request.videoUrl());
            if (ok) cleaner.deleteQuietly(replacedImage(old, request.imageUrl()));
            return ok ? ResponseEntity.ok(Map.of("message", "更新成功"))
                    : ResponseEntity.badRequest().body(Map.of("message", "帖子不存在"));
        }
        boolean ok = posts.update(id, request.userId(), request.board(), request.title(), request.content(),
                request.imageUrl(), request.videoUrl());
        if (ok) cleaner.deleteQuietly(replacedImage(old, request.imageUrl()));
        return ok ? ResponseEntity.ok(Map.of("message", "更新成功"))
                : ResponseEntity.badRequest().body(Map.of("message", "无权编辑或帖子不存在"));
    }

    /** 编辑成功后若图片被更换，返回旧图 URL 以便清理本地文件；未更换返回 null。 */
    private String replacedImage(Post old, String newImageUrl) {
        if (old == null || old.imageUrl() == null || old.imageUrl().isBlank()) return null;
        return old.imageUrl().equals(newImageUrl) ? null : old.imageUrl();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, @RequestParam long userId) {
        var actor = users.findById(userId);
        boolean ok = false;
        if (actor != null && !actor.banned()) {
            boolean scopedModerator = "MODERATOR".equals(actor.userType()) && boards.canManagePost(userId, id);
            ok = "ADMIN".equals(actor.userType()) || scopedModerator
                    ? posts.adminDelete(id) : posts.delete(id, userId);
        }
        return ok ? ResponseEntity.ok(Map.of("message", "删除成功"))
                : ResponseEntity.badRequest().body(Map.of("message", "无权删除或帖子不存在"));
    }

    @GetMapping("/{id}/status")
    public Object status(@PathVariable long id, @RequestParam long userId) {
        var actor = users.findById(userId);
        boolean canManage = actor != null && !actor.banned()
                && ("ADMIN".equals(actor.userType())
                    || "MODERATOR".equals(actor.userType()) && boards.canManagePost(userId, id));
        return Map.of(
                "liked", posts.isLiked(userId, id),
                "favorited", posts.isFavorited(userId, id),
                "canManage", canManage
        );
    }

    public record CreateRequest(long userId, String board, String title, String content, String imageUrl, String videoUrl) {}
    public record UpdateRequest(long userId, String board, String title, String content, String imageUrl, String videoUrl) {}
    public record ReplyRequest(long userId, Long parentId, String content) {}
    public record LikeRequest(long userId) {}
    public record FavoriteRequest(long userId, boolean favorite) {}
}
