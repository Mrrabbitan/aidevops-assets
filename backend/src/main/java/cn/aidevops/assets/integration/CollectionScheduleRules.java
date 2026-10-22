package cn.aidevops.assets.integration;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.integration.CollectionScheduleModels.Save;
import java.time.ZoneId;
import java.util.*;

public final class CollectionScheduleRules {
  private CollectionScheduleRules() {}

  public static Save normalize(Save r) {
    if (r == null || blank(r.projectId()) || blank(r.name()) || r.name().length() > 200)
      throw new ApiException(400, "VALIDATION", "项目和不超过 200 字的名称为必填项");
    if (blank(r.kind())
        || !Set.of("DISCOVERY", "SYNC").contains(r.kind())
        || blank(r.mode())
        || !Set.of("SIMULATED", "REAL").contains(r.mode()))
      throw new ApiException(400, "VALIDATION", "采集类别或模式无效");
    if ("SIMULATED".equals(r.mode()) && !"demo".equals(r.projectId()))
      throw new ApiException(422, "SIMULATION_ISOLATION", "模拟采集只允许 demo 隔离项目");
    int interval = r.intervalMinutes() == null ? 60 : r.intervalMinutes();
    if (interval < 1 || interval > 525600)
      throw new ApiException(400, "VALIDATION", "intervalMinutes 必须为 1–525600 的整数");
    String zone = blank(r.timezone()) ? "Asia/Shanghai" : r.timezone();
    try {
      ZoneId.of(zone);
    } catch (RuntimeException e) {
      throw new ApiException(400, "TIMEZONE_INVALID", "timezone 必须为有效 IANA 时区");
    }
    if (blank(r.serviceSubject()))
      throw new ApiException(400, "SERVICE_REQUIRED", "必须指定独立服务身份 serviceSubject");
    Map<String, Object> config = r.config() == null ? Map.of() : r.config();
    if (config.keySet().stream()
        .anyMatch(k -> !Set.of("connectorId", "networkScope", "assetIds").contains(k)))
      throw new ApiException(
          400, "SCOPE_INVALID", "采集范围只接受 connectorId/networkScope/assetIds，不接受凭据或任意 rows");
    if (blank(text(config.get("connectorId"))))
      throw new ApiException(400, "SCOPE_REQUIRED", "必须明确 connectorId");
    if ("DISCOVERY".equals(r.kind())) {
      if (blank(text(config.get("networkScope"))))
        throw new ApiException(400, "SCOPE_REQUIRED", "发现计划必须明确 networkScope");
      if ("SIMULATED".equals(r.mode())
          && (!"fixture".equals(config.get("connectorId"))
              || !"demo-lan".equals(config.get("networkScope"))))
        throw new ApiException(422, "SCOPE_NOT_SUPPORTED", "模拟发现仅允许 fixture/demo-lan 已定义范围");
    } else {
      var ids = ids(config.get("assetIds"));
      if (ids.isEmpty()
          || ids.size() > 100
          || new HashSet<>(ids).size() != ids.size()
          || ids.stream().anyMatch(String::isBlank))
        throw new ApiException(400, "SCOPE_REQUIRED", "同步计划必须明确 1–100 个不重复的 assetIds，不能默认全池");
      if ("SIMULATED".equals(r.mode()) && !"fixture".equals(config.get("connectorId")))
        throw new ApiException(422, "SCOPE_NOT_SUPPORTED", "模拟同步仅支持 fixture 来源");
    }
    boolean enabled = Boolean.TRUE.equals(r.enabled());
    if (enabled && "REAL".equals(r.mode()))
      throw new ApiException(503, "NOT_CONFIGURED", "真实采集适配器尚未配置，不能启用周期计划");
    return new Save(
        r.projectId(),
        r.name().trim(),
        r.kind(),
        r.mode(),
        r.version(),
        enabled,
        interval,
        zone,
        r.serviceSubject(),
        config);
  }

  public static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  public static List<String> ids(Object value) {
    return value instanceof List<?> l
        ? l.stream().map(CollectionScheduleRules::text).toList()
        : List.of();
  }
}
