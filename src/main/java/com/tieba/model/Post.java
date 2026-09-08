package com.tieba.model;

public record Post(Long id, String boardName, Long userId, String username, String avatar,
                   String createdAt, int likes, int favorites, int views,
                   String title, String content, String imageUrl, String videoUrl) {}
