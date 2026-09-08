package com.tieba.controller;

import com.tieba.repository.PostRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ActivityController {
    private final PostRepository posts;

    public ActivityController(PostRepository posts) {
        this.posts = posts;
    }

    @PostMapping("/favorites")
    public ResponseEntity<?> favorite(@RequestBody FavoriteRequest request) {
        return ResponseEntity.ok(posts.toggleFavorite(request.userId(), request.postId(), request.favorite()));
    }

    @PostMapping("/history")
    public ResponseEntity<?> history(@RequestBody HistoryRequest request) {
        posts.history(request.userId(), request.postId());
        return ResponseEntity.ok(Map.of("message", "记录成功"));
    }

    public record FavoriteRequest(long userId, long postId, boolean favorite) {}
    public record HistoryRequest(long userId, long postId) {}
}
