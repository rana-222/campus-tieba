package com.tieba.controller;

import com.tieba.repository.NoticeRepository;
import com.tieba.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 校园公告：所有人可读，仅 ADMIN 可编辑（入口在 设置-管理功能）。 */
@RestController
@RequestMapping("/api/notice")
public class NoticeController {
    private final NoticeRepository notices;
    private final UserRepository users;

    public NoticeController(NoticeRepository notices, UserRepository users) {
        this.notices = notices;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        var notice = notices.find();
        return notice == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(Map.of("title", notice.title(), "content", notice.content()));
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody NoticeRequest request) {
        var actor = users.findById(request.userId());
        if (actor == null || actor.banned() || !"ADMIN".equals(actor.userType())) {
            return ResponseEntity.status(403).body(Map.of("message", "仅高级管理员可编辑校园公告"));
        }
        String title = request.title() == null ? "" : request.title().trim();
        String content = request.content() == null ? "" : request.content().trim();
        if (title.isEmpty() || title.length() > 100 || content.isEmpty() || content.length() > 500) {
            return ResponseEntity.badRequest().body(Map.of("message", "公告标题（100 字内）与内容（500 字内）均不能为空"));
        }
        notices.save(title, content);
        return ResponseEntity.ok(Map.of("message", "校园公告已更新", "title", title, "content", content));
    }

    public record NoticeRequest(long userId, String title, String content) {}
}
