package com.tieba.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 集中解析上传目录路径，并在启动时确保目录可创建。
 * 解析顺序：
 *   1. 优先使用 tieba.upload-dir 配置（默认 ../tieba-data，与 jar 同级，替换 jar 不丢数据）；
 *   2. 若该目录无法创建（权限不足/路径受限），回退到项目内 uploads 目录并打印警告。
 * DatabaseInitializer / WebConfig / UploadController 统一注入本 Bean，避免多处重复逻辑。
 */
@Component
public class UploadPathConfig {

    @Value("${tieba.upload-dir:../tieba-data}")
    private String configuredDir;

    private Path resolvedPath;

    @PostConstruct
    public void init() {
        Path primary = Path.of(configuredDir).toAbsolutePath().normalize();
        if (tryUse(primary)) return;
        Path fallback = Path.of("uploads").toAbsolutePath().normalize();
        if (tryUse(fallback)) {
            System.err.println("[UploadPathConfig] 主上传目录 " + primary
                    + " 不可写入，已回退到 " + fallback);
            return;
        }
        throw new IllegalStateException("无法初始化任何可写的上传目录");
    }

    private boolean tryUse(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-probe");
            try {
                Files.writeString(probe, "ok");
            } finally {
                Files.deleteIfExists(probe);
            }
            resolvedPath = dir;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回最终使用的上传目录绝对路径（已确保存在）。 */
    public Path uploadDir() {
        return resolvedPath;
    }
}
