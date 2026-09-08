package com.tieba.controller;

import com.tieba.model.SearchResult;
import com.tieba.repository.PostRepository;
import com.tieba.repository.UserRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserRepository users;
    private final PostRepository posts;

    public UserController(UserRepository users, PostRepository posts) {
        this.users = users;
        this.posts = posts;
    }

    @GetMapping("/{id}")
    public Object profile(@PathVariable long id) {
        var user = users.findById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return Map.of("user", user, "stats", users.stats(id));
    }

    @GetMapping("/{id}/posts")
    public Object userPosts(@PathVariable long id) {
        return posts.findByUserId(id);
    }

    @GetMapping("/{id}/favorites")
    public Object userFavorites(@PathVariable long id) {
        return posts.findFavoritesByUserId(id);
    }

    @GetMapping("/{id}/history")
    public Object userHistory(@PathVariable long id) {
        return posts.findHistoryByUserId(id);
    }

    @GetMapping("/{id}/stats")
    public Object stats(@PathVariable long id) {
        return users.stats(id);
    }

    @GetMapping("/search")
    public Object search(@RequestParam String q,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "50") int size) {
        var result = users.search(q, page, size);
        var items = result.items().stream().map(user -> {
            var m = new java.util.HashMap<String, Object>();
            m.put("id", user.id());
            m.put("account", user.account());
            m.put("username", user.username());
            m.put("avatar", user.avatar());
            return m;
        }).toList();
        return new SearchResult<>(items, result.total(), result.page(), result.size(), result.hasMore());
    }

    @GetMapping("/{id}/notifications")
    public Object notifications(@PathVariable long id) {
        return users.notifications(id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody ProfileRequest request) {
        var current = users.findById(request.userId());
        if (current == null || current.banned() || (current.id() != id && !"ADMIN".equals(current.userType()))) {
            return ResponseEntity.status(403).body(Map.of("message", "无权修改该资料"));
        }
        var existing = users.findByAccount(request.account());
        if (existing != null && !existing.id().equals(id)) {
            return ResponseEntity.badRequest().body(Map.of("message", "登录用户名已存在"));
        }
        String password = request.password() == null || request.password().isBlank() ? null : request.password();
        if (password != null && !password.matches("^\\S{8,32}$")) {
            return ResponseEntity.badRequest().body(Map.of("message", "密码需为8-32位且不能有空格"));
        }
        users.updateProfile(id, request.account(), request.username(), password, request.backgroundUrl());
        return ResponseEntity.ok(Map.of("user", users.findById(id)));
    }

    @PostMapping("/{id}/ban")
    public ResponseEntity<?> ban(@PathVariable long id, @RequestBody ActorRequest request) {
        var actor = users.findById(request.userId());
        if (actor == null || !"ADMIN".equals(actor.userType())) return ResponseEntity.status(403).body(Map.of("message", "仅高级管理员可封禁账号"));
        users.setBanned(id, request.banned());
        return ResponseEntity.ok(Map.of("message", request.banned() ? "账号已封禁" : "账号已解封"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, @RequestParam long userId) {
        var actor = users.findById(userId);
        if (actor == null || !"ADMIN".equals(actor.userType())) {
            return ResponseEntity.status(403).body(Map.of("message", "仅高级管理员可删除用户"));
        }
        if (id == userId) {
            return ResponseEntity.badRequest().body(Map.of("message", "不能删除当前登录账号"));
        }
        var target = users.findById(id);
        if (target == null) return ResponseEntity.notFound().build();
        if ("ADMIN".equals(target.userType())) {
            return ResponseEntity.badRequest().body(Map.of("message", "不能删除管理员账号"));
        }
        users.delete(id);
        return ResponseEntity.ok(Map.of("message", "用户已删除"));
    }

    public record ProfileRequest(long userId, @NotBlank String account, @NotBlank String username,
                                 String password, String backgroundUrl) {}
    public record ActorRequest(long userId, boolean banned) {}
}
