package com.tieba.service;

import com.tieba.config.UploadPathConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;

/**
 * 上传文件清理：按 /uploads/** 相对 URL 删除本地旧文件。
 * 仅允许删除上传目录内的安全文件名（拒绝路径穿越），删除失败仅记录日志、不影响业务。
 */
@Component
public class UploadFileCleaner {
    private static final Logger log = LoggerFactory.getLogger(UploadFileCleaner.class);
    private static final String URL_PREFIX = "/uploads/";

    private final UploadPathConfig uploadPath;

    public UploadFileCleaner(UploadPathConfig uploadPath) {
        this.uploadPath = uploadPath;
    }

    /** URL 合法（/uploads/ 下安全文件名）时删除对应本地文件，否则忽略。 */
    public void deleteQuietly(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) return;
        String name = url.substring(URL_PREFIX.length());
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) return;
        try {
            Files.deleteIfExists(uploadPath.uploadDir().resolve(name));
        } catch (IOException e) {
            log.warn("清理旧上传文件失败 {}: {}", url, e.getMessage());
        }
    }

    /** 仅当 URL 文件名以指定前缀开头时删除（如 avatar-{userId}-，确保只删该用户自己的旧文件）。 */
    public void deleteIfOwned(String url, String filenamePrefix) {
        if (url != null && url.startsWith(URL_PREFIX + filenamePrefix)) deleteQuietly(url);
    }
}
