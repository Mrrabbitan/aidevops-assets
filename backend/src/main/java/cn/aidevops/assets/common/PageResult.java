package cn.aidevops.assets.common;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int pageSize) {}
