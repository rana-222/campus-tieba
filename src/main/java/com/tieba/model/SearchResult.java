package com.tieba.model;

import java.util.List;

public record SearchResult<T>(List<T> items, long total, int page, int size, boolean hasMore) {}
