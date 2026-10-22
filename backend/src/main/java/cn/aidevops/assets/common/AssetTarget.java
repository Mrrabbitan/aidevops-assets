package cn.aidevops.assets.common;

import java.util.Map;

public record AssetTarget(
    String id,
    String name,
    String type,
    String mode,
    String admission,
    String resourceStatus,
    long version,
    Map<String, Object> technical) {}
