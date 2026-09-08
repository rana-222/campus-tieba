package com.tieba.model;

public record User(Long id, String account, String username, String avatar, String backgroundUrl,
				   String userType, boolean banned) {}
