CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(120) NOT NULL,
    username VARCHAR(50) NOT NULL,
    avatar VARCHAR(255) DEFAULT NULL,
    background_url VARCHAR(500) DEFAULT NULL,
    banned BOOLEAN NOT NULL DEFAULT FALSE,
    user_type ENUM('USER','ADMIN','MODERATOR') NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS boards (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL UNIQUE,
    icon VARCHAR(255),
    parent_id BIGINT DEFAULT NULL,
    creator_id BIGINT NOT NULL,
    hot_score INT NOT NULL DEFAULT 0,
    INDEX idx_boards_creator(creator_id),
    INDEX idx_boards_parent(parent_id)
);

CREATE TABLE IF NOT EXISTS board_moderators (
    user_id BIGINT NOT NULL,
    board_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, board_id),
    INDEX idx_board_moderators_board(board_id)
);

CREATE TABLE IF NOT EXISTS posts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    board_name VARCHAR(50) NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    likes INT NOT NULL DEFAULT 0,
    favorites INT NOT NULL DEFAULT 0,
    views INT NOT NULL DEFAULT 0,
    title VARCHAR(150) NOT NULL,
    content TEXT NOT NULL,
    image_url VARCHAR(500) DEFAULT '',
    video_url VARCHAR(500) DEFAULT '',
    INDEX idx_posts_board_time(board_name, created_at),
    INDEX idx_posts_user(user_id)
);

CREATE TABLE IF NOT EXISTS user_follows (
    user_id BIGINT NOT NULL,
    board_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, board_id),
    INDEX idx_user_follows_board(board_id)
);

CREATE TABLE IF NOT EXISTS user_likes (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id),
    INDEX idx_user_likes_post(post_id)
);

CREATE TABLE IF NOT EXISTS user_favorites (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id),
    INDEX idx_user_favorites_post(post_id)
);

CREATE TABLE IF NOT EXISTS browsing_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    viewed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_history_user_post (user_id, post_id),
    INDEX idx_history_user_time(user_id, viewed_at),
    INDEX idx_history_post(post_id)
);

CREATE TABLE IF NOT EXISTS replies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT DEFAULT NULL,
    content VARCHAR(1000) NOT NULL,
    likes INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_replies_post_time(post_id, created_at),
    INDEX idx_replies_user(user_id)
);

CREATE TABLE IF NOT EXISTS reply_likes (
    user_id BIGINT NOT NULL,
    reply_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, reply_id),
    INDEX idx_reply_likes_reply(reply_id)
);

CREATE TABLE IF NOT EXISTS site_notice (
    id TINYINT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
