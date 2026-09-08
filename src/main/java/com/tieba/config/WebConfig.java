package com.tieba.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final UploadPathConfig uploadPath;

    public WebConfig(UploadPathConfig uploadPath) {
        this.uploadPath = uploadPath;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传文件名是 UUID，内容不可变，可长缓存。
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath.uploadDir() + "/")
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic().immutable());
    }
}
