package com.tieba.controller;

import com.tieba.config.UploadPathConfig;
import com.tieba.repository.UserRepository;
import com.tieba.service.UploadFileCleaner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class UploadController {
    private static final long MAX_AVATAR_SIZE = 5 * 1024 * 1024;

    private final UserRepository users;
    private final UploadPathConfig uploadPath;
    private final UploadFileCleaner cleaner;

    public UploadController(UserRepository users, UploadPathConfig uploadPath, UploadFileCleaner cleaner) {
        this.users = users;
        this.uploadPath = uploadPath;
        this.cleaner = cleaner;
    }

    @PostMapping("/avatar")
    public ResponseEntity<?> uploadAvatar(@RequestParam long userId,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择头像文件"));
        }
        if (file.getSize() > MAX_AVATAR_SIZE || file.getContentType() == null
                || !file.getContentType().toLowerCase().startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("message", "头像必须是 5MB 以内的图片"));
        }

        String contentType = file.getContentType().toLowerCase();
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> null;
        };
        if (extension == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "仅支持 JPG、PNG、GIF 或 WebP 图片"));
        }
        var existing = users.findById(userId);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        String filename = "avatar-" + userId + "-" + UUID.randomUUID() + extension;
        Path dir = uploadPath.uploadDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        try (var input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String url = "/uploads/" + filename;
        users.updateAvatar(userId, url);
        // 数据库更新成功后，清理该用户的旧头像本地文件
        cleaner.deleteIfOwned(existing.avatar(), "avatar-" + userId + "-");
        return ResponseEntity.ok(Map.of("url", url));
    }

    @PostMapping("/background")
    public ResponseEntity<?> uploadBackground(@RequestParam long userId,
                                              @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024
                || file.getContentType() == null || !file.getContentType().toLowerCase().startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("message", "背景图必须是 10MB 以内的图片"));
        }
        var existing = users.findById(userId);
        if (existing == null) return ResponseEntity.notFound().build();
        String contentType = file.getContentType().toLowerCase();
        String extension = contentType.equals("image/png") ? ".png" : contentType.equals("image/webp") ? ".webp" : ".jpg";
        String filename = "background-" + userId + "-" + UUID.randomUUID() + extension;
        Path dir = uploadPath.uploadDir();
        Files.createDirectories(dir);
        Files.copy(file.getInputStream(), dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        String url = "/uploads/" + filename;
        users.updateBackground(userId, url);
        // 数据库更新成功后，清理该用户的旧背景本地文件
        cleaner.deleteIfOwned(existing.backgroundUrl(), "background-" + userId + "-");
        return ResponseEntity.ok(Map.of("url", url));
    }

    @PostMapping("/board-icon")
    public ResponseEntity<?> uploadBoardIcon(@RequestParam long userId,
                                             @RequestParam("file") MultipartFile file) throws IOException {
        if (!isAdmin(userId)) return ResponseEntity.status(403).body(Map.of("message", "仅高级管理员可上传版面图标"));
        if (file.isEmpty() || file.getSize() > MAX_AVATAR_SIZE
                || file.getContentType() == null || !file.getContentType().toLowerCase().startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("message", "版面图标必须是 5MB 以内的图片"));
        }
        String contentType = file.getContentType().toLowerCase();
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> null;
        };
        if (extension == null) return ResponseEntity.badRequest().body(Map.of("message", "仅支持 JPG、PNG、GIF 或 WebP 图片"));
        String filename = "board-icon-" + userId + "-" + UUID.randomUUID() + extension;
        Path dir = uploadPath.uploadDir();
        Files.createDirectories(dir);
        Files.copy(file.getInputStream(), dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        return ResponseEntity.ok(Map.of("url", "/uploads/" + filename));
    }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件为空"));
        }
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.'));
        }
        String filename = UUID.randomUUID() + ext;
        Path dir = uploadPath.uploadDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        try (var input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String url = "/uploads/" + filename;
        boolean isVideo = ext.matches("(?i)\\.(mp4|webm|mov|avi|mkv)");
        return ResponseEntity.ok(Map.of("url", url, "type", isVideo ? "video" : "image"));
    }

    private boolean isAdmin(long userId) {
        var user = users.findById(userId);
        return user != null && !user.banned() && "ADMIN".equals(user.userType());
    }
}
