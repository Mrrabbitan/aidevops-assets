package cn.aidevops.assets.task;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.task.TaskModels.SaveRequest;
import java.util.*;

/** Pure validation is shared by preview, freezing and enqueueing. Secrets are references only. */
public final class TaskRules {
  private TaskRules() {}

  private static final Map<String, Set<String>> TYPES =
      Map.of(
          "CLUSTER",
          Set.of("PVE", "K8S"),
          "VM",
          Set.of("GENERAL", "COMPUTE", "STORAGE"),
          "ENVIRONMENT",
          Set.of(
              "CONTAINER_PLATFORM",
              "MICROSERVICE_FRAMEWORK",
              "CLIENT_ENVIRONMENT",
              "HOTUPDATE_SERVER"),
          "DEPLOYMENT",
          Set.of("APPLICATION"),
          "DISTRIBUTION",
          Set.of("FLATPAK", "CLIENT_PUSH", "FLUTTER_HOTUPDATE"));
  private static final Map<String, List<String>> REQUIRED =
      Map.ofEntries(
          Map.entry(
              "CLUSTER",
              List.of(
                  "platformVersion",
                  "physicalAssetIds",
                  "roles",
                  "network",
                  "storage",
                  "credentialRef")),
          Map.entry(
              "VM",
              List.of(
                  "clusterId",
                  "hostId",
                  "template",
                  "cpu",
                  "memoryGb",
                  "diskGb",
                  "storagePool",
                  "networkBridge",
                  "ip",
                  "credentialRef")),
          Map.entry(
              "CONTAINER_PLATFORM",
              List.of("platform", "version", "nodeRoles", "network", "storage")),
          Map.entry("MICROSERVICE_FRAMEWORK", List.of("version", "namespace", "storage", "domain")),
          Map.entry(
              "CLIENT_ENVIRONMENT", List.of("version", "repository", "credentialRef", "strategy")),
          Map.entry(
              "HOTUPDATE_SERVER",
              List.of("version", "port", "directory", "credentialRef", "strategy")),
          Map.entry(
              "DEPLOYMENT",
              List.of(
                  "artifact",
                  "artifactDigest",
                  "environment",
                  "namespace",
                  "strategy",
                  "healthCheck",
                  "timeoutSeconds",
                  "failurePolicy")),
          Map.entry(
              "FLATPAK",
              List.of("app", "artifact", "version", "architecture", "runtime", "repository")),
          Map.entry(
              "CLIENT_PUSH", List.of("app", "version", "repository", "strategy", "threshold")),
          Map.entry(
              "FLUTTER_HOTUPDATE",
              List.of(
                  "app",
                  "baseVersion",
                  "artifact",
                  "version",
                  "credentialRef",
                  "module",
                  "entry",
                  "strategy",
                  "validation")));

  public static void shape(SaveRequest r) {
    if (r == null || blank(r.projectId()) || blank(r.name()) || r.name().length() > 200)
      throw new ApiException(400, "VALIDATION", "项目和不超过 200 字的任务名称为必填项");
    if (blank(r.kind())
        || blank(r.type())
        || !TYPES.getOrDefault(r.kind(), Set.of()).contains(r.type()))
      throw new ApiException(400, "VALIDATION", "任务类别或类型无效");
    if (blank(r.mode()) || !Set.of("SIMULATED", "REAL").contains(r.mode()))
      throw new ApiException(400, "VALIDATION", "执行模式必须为 SIMULATED 或 REAL");
    if (r.config() == null) throw new ApiException(400, "VALIDATION", "config 为必填对象");
    rejectSecrets(r.config());
  }

  public static List<String> errors(String kind, String type, Map<String, Object> c) {
    var errors = new ArrayList<String>();
    String rule = Set.of("CLUSTER", "VM", "DEPLOYMENT").contains(kind) ? kind : type;
    for (String key : REQUIRED.getOrDefault(rule, List.of()))
      if (empty(c.get(key))) errors.add(key + " 为必填配置");
    List<String> targets = strings(c.get("targetIds"));
    if (targets.isEmpty()) errors.add("至少选择一个 targetIds 目标");
    if (targets.size() > 100) errors.add("单个任务最多 100 个目标");
    if (new HashSet<>(targets).size() != targets.size()) errors.add("targetIds 不能重复");
    if ("CLUSTER".equals(kind)
        && !new HashSet<>(targets).equals(new HashSet<>(strings(c.get("physicalAssetIds")))))
      errors.add("集群目标必须与 physicalAssetIds 物理节点一致");
    if ("CLUSTER".equals(kind) && "K8S".equals(type) && empty(c.get("serviceNetwork")))
      errors.add("K8S serviceNetwork 为必填配置");
    if ("VM".equals(kind)) {
      positive(c, "cpu", 1, 1024, errors);
      positive(c, "memoryGb", 1, 1048576, errors);
      positive(c, "diskGb", 1, 1048576, errors);
      if (targets.size() != 1 || !targets.contains(text(c.get("hostId"))))
        errors.add("VM 的 targetIds 必须只包含所选 hostId 宿主机");
    }
    if ("HOTUPDATE_SERVER".equals(type)) positive(c, "port", 1, 65535, errors);
    if ("DEPLOYMENT".equals(kind)) {
      positive(c, "timeoutSeconds", 1, 86400, errors);
      if (!text(c.get("artifactDigest")).matches("sha256:[0-9a-fA-F]{64}"))
        errors.add("artifactDigest 必须为 sha256 摘要");
      if ("CANARY".equalsIgnoreCase(text(c.get("strategy"))) || "灰度".equals(c.get("strategy")))
        positive(c, "canaryRatio", 1, 99, errors);
      if ("ROLLBACK".equalsIgnoreCase(text(c.get("failurePolicy")))
          && empty(c.get("rollbackBaseline"))) errors.add("自动回退必须指定已成功的 rollbackBaseline");
    }
    if ("CLIENT_PUSH".equals(type)) positive(c, "threshold", 1, 100, errors);
    var schedule = map(c.get("schedule"));
    if (Boolean.TRUE.equals(schedule.get("enabled"))) {
      positive(schedule, "intervalMinutes", 1, 525600, errors);
      if (empty(schedule.get("serviceSubject"))) errors.add("启用定时任务必须指定 serviceSubject");
    }
    return errors;
  }

  private static void positive(
      Map<String, Object> c, String key, double min, double max, List<String> errors) {
    try {
      double n = Double.parseDouble(text(c.get(key)));
      if (!Double.isFinite(n) || n < min || n > max || Math.floor(n) != n)
        throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      errors.add(key + " 必须为 " + (int) min + "–" + (int) max + " 的整数");
    }
  }

  public static void rejectSecrets(Object value) {
    if (value instanceof Map<?, ?> map)
      map.forEach(
          (key, v) -> {
            String s =
                String.valueOf(key).toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            if (Set.of(
                    "password",
                    "token",
                    "secret",
                    "privatekey",
                    "kubeconfig",
                    "authorization",
                    "accesskey",
                    "secretkey")
                .contains(s))
              throw new ApiException(400, "SECRET_IN_CONFIG", "连接密钥请使用 credentialRef，禁止保存原始凭据");
            rejectSecrets(v);
          });
    else if (value instanceof List<?> list) list.forEach(TaskRules::rejectSecrets);
  }

  public static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  public static boolean empty(Object v) {
    return v == null
        || (v instanceof String s && s.isBlank())
        || (v instanceof Collection<?> c && c.isEmpty())
        || (v instanceof Map<?, ?> m && m.isEmpty());
  }

  public static String text(Object v) {
    return v == null ? "" : String.valueOf(v);
  }

  public static List<String> strings(Object o) {
    return o instanceof List<?> l ? l.stream().map(TaskRules::text).toList() : List.of();
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }
}
