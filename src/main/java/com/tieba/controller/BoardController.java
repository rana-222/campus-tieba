package com.tieba.controller;

import com.tieba.repository.BoardRepository;
import com.tieba.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/boards")
public class BoardController {
    private final BoardRepository boards;
    private final UserRepository users;

    public BoardController(BoardRepository boards, UserRepository users) {
        this.boards = boards;
        this.users = users;
    }

    @GetMapping
    public Object list() {
        return boards.findAll();
    }

    @GetMapping("/followed")
    public Object followed(@RequestParam long userId) {
        return boards.followedByUser(userId);
    }

    @GetMapping("/status")
    public Object status(@RequestParam long userId, @RequestParam String board) {
        return Map.of("following", boards.isFollowing(userId, board));
    }

    @GetMapping("/children")
    public Object children(@RequestParam String parent) {
        return boards.children(parent);
    }

    @PostMapping("/follow")
    public ResponseEntity<?> follow(@RequestBody FollowRequest request) {
        return ResponseEntity.ok(boards.toggleFollow(request.userId(), request.board(), request.follow()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody BoardRequest request) {
        if (!canCreate(request)) return ResponseEntity.status(403).body(Map.of("message", "无权创建该版面"));
        try {
            boards.create(request.name(), request.icon(), request.parentName(), request.userId());
            return ResponseEntity.ok(Map.of("message", "版面已创建"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "版面名称已存在或不合法"));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name, @RequestParam long userId) {
        if (!isAdmin(userId) && !boards.canManageBoard(userId, name)) {
            return ResponseEntity.status(403).body(Map.of("message", "版主只能管理所属主版面下的子版面"));
        }
        return boards.delete(name) > 0 ? ResponseEntity.ok(Map.of("message", "版面已删除"))
                : ResponseEntity.notFound().build();
    }

    private boolean canCreate(BoardRequest request) {
        if (isAdmin(request.userId())) return true;
        return request.parentName() != null && !request.parentName().isBlank()
                && boards.isManagedParent(request.userId(), request.parentName());
    }

    @PostMapping("/moderators")
    public ResponseEntity<?> assignModerator(@RequestBody ModeratorRequest request) {
        if (!isAdmin(request.userId())) return ResponseEntity.status(403).body(Map.of("message", "仅高级管理员可设置版主"));
        var target = users.findById(request.targetUserId());
        if (target == null || target.banned() || "ADMIN".equals(target.userType())) {
            return ResponseEntity.badRequest().body(Map.of("message", "目标用户不存在、已封禁或不能设置管理员"));
        }
        if (!boards.assignModerator(request.targetUserId(), request.boardName(), request.enabled())) {
            return ResponseEntity.badRequest().body(Map.of("message", "只能选择主版面"));
        }
        users.setUserType(request.targetUserId(), request.enabled() ? "MODERATOR" : "USER");
        if (!request.enabled() && boards.moderatorAssignmentCount(request.targetUserId()) > 0) {
            users.setUserType(request.targetUserId(), "MODERATOR");
        }
        return ResponseEntity.ok(Map.of("message", request.enabled() ? "版主已设置" : "版主权限已撤销"));
    }

    private boolean isAdmin(long userId) {
        var user = users.findById(userId);
        return user != null && !user.banned() && "ADMIN".equals(user.userType());
    }

    public record FollowRequest(long userId, String board, boolean follow) {}
    public record BoardRequest(long userId, String name, String icon, String parentName) {}
    public record ModeratorRequest(long userId, long targetUserId, String boardName, boolean enabled) {}
}
