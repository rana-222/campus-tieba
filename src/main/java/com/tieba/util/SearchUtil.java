package com.tieba.util;

import java.util.Arrays;
import java.util.List;

public final class SearchUtil {
    private SearchUtil() {}

    /**
     * 把搜索关键词拆分为若干独立词条：按空白与常见中英文标点切分，
     * 忽略空串、去重并统一转小写，使"高数 复习"这类多词查询能分别命中。
     */
    public static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.toLowerCase().split("[\\s\\u3000，,。.、;；:：!！?？/|]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
