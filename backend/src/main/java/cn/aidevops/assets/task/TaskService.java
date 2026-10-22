package cn.aidevops.assets.task;

import static cn.aidevops.assets.task.TaskRules.*;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import cn.aidevops.assets.security.Access.Actor;
import cn.aidevops.assets.task.TaskModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
  private final TaskRepository repo;
  private final Access access;
  private final AssetsPort assets;
  private final Audit audit;

  public TaskService(TaskRepository repo, Access access, AssetsPort assets, Audit audit) {
    this.repo = repo;
    this.access = access;
    this.assets = assets;
    this.audit = audit;
  }

  public List<Task> list(String projectId, String kind) {
    access.require(projectId, "task.read");
    return repo.tasks(projectId, kind);
  }

  public Task get(String id) {
    var t = repo.task(id, false);
    access.require(t.projectId(), "task.read");
    return t;
  }

  public Task create(SaveRequest request) {
    shape(request);
    access.require(request.projectId(), "task.write");
    try {
      return repo.transaction(
          () -> {
            var t = repo.insert(request, access.actor().subject());
            configureSchedule(t);
            audit.record(
                t.projectId(), "task.created", t.id(), Map.of("kind", t.kind(), "mode", t.mode()));
            return repo.task(t.id(), false);
          });
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(409, "DUPLICATE_NAME", "同项目任务名称已存在");
    }
  }

  public Task update(String id, SaveRequest request) {
    return repo.transaction(
        () -> {
          var old = repo.task(id, true);
          access.require(old.projectId(), "task.write");
          version(old, request.version());
          var full =
              new SaveRequest(
                  old.projectId(),
                  request.name(),
                  old.kind(),
                  request.type(),
                  request.mode(),
                  request.version(),
                  request.config());
          shape(full);
          if (repo.active(id)) throw new ApiException(409, "ACTIVE_RUN", "任务存在活动或结果未知的执行，不能修改");
          if ("DEPLOYMENT".equals(old.kind())
              && repo.hasRuns(id)
              && !Objects.equals(
                  old.config().get("environment"),
                  request.config() == null ? null : request.config().get("environment")))
            throw new ApiException(409, "ENVIRONMENT_IMMUTABLE", "已有执行的部署任务不能更换环境，请新建任务");
          if (request.projectId() != null && !old.projectId().equals(request.projectId()))
            throw new ApiException(400, "PROJECT_IMMUTABLE", "任务不能转移项目");
          var t = repo.update(old, full);
          configureSchedule(t);
          audit.record(t.projectId(), "task.updated", id, Map.of("version", t.version()));
          return repo.task(id, false);
        });
  }

  public void delete(String id, Long version) {
    repo.transaction(
        () -> {
          var t = repo.task(id, true);
          access.require(t.projectId(), "task.write");
          version(t, version);
          if (!Set.of("DRAFT", "VALIDATED").contains(t.status()) || repo.hasRuns(id))
            throw new ApiException(409, "NOT_DRAFT", "只允许删除从未执行的草稿任务");
          repo.delete(id);
          audit.record(t.projectId(), "task.deleted", id, Map.of("version", t.version()));
          return null;
        });
  }

  public Validation validate(String id, Long version) {
    return repo.transaction(
        () -> {
          var t = repo.task(id, true);
          access.require(t.projectId(), "task.write");
          version(t, version);
          if (repo.active(id)) throw new ApiException(409, "ACTIVE_RUN", "执行中或结果未知的任务不能重新校验");
          var errors = errors(t.kind(), t.type(), t.config());
          if (errors.isEmpty())
            try {
              check(t, access.actor());
            } catch (ApiException e) {
              errors.add(e.getMessage());
            }
          if (errors.isEmpty() && !"CONFIRMED".equals(t.status())) repo.validated(id);
          audit.record(
              t.projectId(),
              "task.validated",
              id,
              Map.of("valid", errors.isEmpty(), "errors", errors));
          return new Validation(
              errors.isEmpty(),
              List.copyOf(errors),
              t.version(),
              "REAL".equals(t.mode()) ? "REAL_NOT_CONFIGURED" : "SIMULATED");
        });
  }

  public Task confirm(String id, Long version) {
    return repo.transaction(
        () -> {
          var t = repo.task(id, true);
          access.require(t.projectId(), "deployment.confirm");
          version(t, version);
          if (repo.active(id)) throw new ApiException(409, "ACTIVE_RUN", "执行中或结果未知的任务不能确认");
          requireConfig(t);
          var targets = check(t, access.actor());
          var snapshot = executionSnapshot(t, targets);
          snapshot.put("confirmedBy", access.actor().subject());
          repo.confirmed(t, snapshot);
          audit.record(
              t.projectId(),
              "task.confirmed",
              id,
              Map.of(
                  "version",
                  t.version(),
                  "artifact",
                  text(t.config().get("artifact")),
                  "artifactDigest",
                  text(t.config().get("artifactDigest"))));
          return repo.task(id, false);
        });
  }

  public Run execute(String id, ExecuteRequest request) {
    var task = repo.task(id, false);
    access.require(task.projectId(), "task.execute");
    return enqueue(id, request, access.actor(), false);
  }

  Run enqueue(String id, ExecuteRequest request, Actor actor, boolean scheduled) {
    if (request == null
        || request.version() == null
        || blank(request.idempotencyKey())
        || !request.idempotencyKey().matches("[A-Za-z0-9:_.-]{8,200}"))
      throw new ApiException(400, "VALIDATION", "version 与 8–200 字符 idempotencyKey 为必填项");
    String hash = hash(id + ":" + request.version() + ":execute");
    try {
      return repo.transaction(
          () -> {
            var t = repo.task(id, true);
            access.authorizeSnapshot(actor, t.projectId(), "task.execute");
            var receipt = repo.receipt(t.projectId(), request.idempotencyKey(), hash);
            if (receipt.isPresent()) return receipt.get();
            version(t, request.version());
            if (repo.active(id))
              throw new ApiException(409, "ACTIVE_RUN", "任务已有执行；UNKNOWN 必须先核查，禁止重放");
            if ("REAL".equals(t.mode()))
              throw new ApiException(503, "NOT_CONFIGURED", "真实执行适配器尚未配置；未执行或生成成功结果");
            requireConfig(t);
            var targets = check(t, actor);
            boolean production = production(t, targets);
            if (production) {
              access.authorizeSnapshot(actor, t.projectId(), "deployment.confirm");
              if (!Objects.equals(t.confirmedVersion(), t.version()))
                throw new ApiException(409, "CONFIRMATION_REQUIRED", "生产操作必须确认并冻结当前任务版本");
              verifyConfirmation(t, targets);
            }
            if (scheduled && !actor.service())
              throw new ApiException(403, "SERVICE_REQUIRED", "定时任务必须使用独立服务身份");
            if ("VM".equals(t.kind())) repo.checkCapacity(t, targets);
            String runId =
                repo.enqueue(
                    t,
                    executionSnapshot(t, targets),
                    actor,
                    request.idempotencyKey(),
                    hash,
                    targetMaps(targets));
            audit.record(
                t.projectId(),
                scheduled ? "task.scheduled" : "task.enqueued",
                id,
                Map.of(
                    "runId",
                    runId,
                    "actor",
                    actor.subject(),
                    "mode",
                    t.mode(),
                    "version",
                    t.version()));
            return repo.run(runId);
          });
    } catch (DataIntegrityViolationException e) {
      var t = repo.task(id, false);
      var receipt = repo.receipt(t.projectId(), request.idempotencyKey(), hash);
      if (receipt.isPresent()) return receipt.get();
      throw new ApiException(409, "ACTIVE_RUN", "任务已有活动执行或幂等键冲突");
    }
  }

  public List<Run> runs(String project) {
    access.require(project, "task.read");
    return repo.runs(project);
  }

  public Run run(String id) {
    var run = repo.run(id);
    access.require(run.projectId(), "task.read");
    return run;
  }

  public List<Log> logs(String id) {
    var run = run(id);
    return repo.logs(run.id());
  }

  public Run rollback(String id, ExecuteRequest request) {
    var source = run(id);
    access.require(source.projectId(), "task.execute");
    access.require(source.projectId(), "deployment.confirm");
    if (request == null
        || request.version() == null
        || blank(request.idempotencyKey())
        || !request.idempotencyKey().matches("[A-Za-z0-9:_.-]{8,200}"))
      throw new ApiException(400, "VALIDATION", "version 与 idempotencyKey 为必填项");
    String hash = hash(id + ":" + request.version() + ":rollback");
    return repo.transaction(
        () -> {
          var t = repo.task(source.taskId(), true);
          version(t, request.version());
          var receipt = repo.receipt(t.projectId(), request.idempotencyKey(), hash);
          if (receipt.isPresent()) return receipt.get();
          if (!"SIMULATED".equals(source.mode()))
            throw new ApiException(503, "NOT_CONFIGURED", "真实回退适配器尚未配置");
          if (!"DEPLOYMENT".equals(source.snapshot().get("kind"))
              || !"SUCCEEDED".equals(source.status())
              || Boolean.TRUE.equals(source.snapshot().get("rollback")))
            throw new ApiException(422, "ROLLBACK_NOT_AVAILABLE", "只允许对成功部署发起回退");
          if (repo.active(t.id())) throw new ApiException(409, "ACTIVE_RUN", "存在活动或结果未知的执行");
          if (!repo.currentSimulation(source))
            throw new ApiException(409, "STALE_DEPLOYMENT", "此执行已不是当前部署版本");
          var snapshot = new LinkedHashMap<>(source.snapshot());
          snapshot.put("version", t.version());
          snapshot.put("rollback", true);
          snapshot.put("rollbackOf", source.id());
          var previous = repo.previousDeployment(source);
          snapshot.put("withdraw", previous.isEmpty());
          snapshot.put(
              "restoreConfig",
              previous
                  .map(v -> v.snapshot().getOrDefault("restoreConfig", v.snapshot().get("config")))
                  .orElse(Map.of()));
          snapshot.put("restoreRunId", previous.map(Run::id).orElse(""));
          var frozen = TaskRules.strings(map(snapshot.get("config")).get("targetIds"));
          var targets = assets.requireTargets(t.projectId(), frozen, true);
          snapshot.put("targets", targetMaps(targets));
          var frozenTask =
              new Task(
                  t.id(),
                  t.projectId(),
                  t.name(),
                  t.kind(),
                  text(source.snapshot().get("type")),
                  t.mode(),
                  t.version(),
                  t.status(),
                  map(source.snapshot().get("config")),
                  t.confirmedVersion(),
                  t.createdAt(),
                  t.updatedAt());
          check(frozenTask, access.actor());
          snapshot.put("dependencies", dependencies(frozenTask));
          var actor = access.actor();
          String newId =
              repo.enqueue(t, snapshot, actor, request.idempotencyKey(), hash, targetMaps(targets));
          audit.record(
              t.projectId(),
              "deployment.rollback.requested",
              t.id(),
              Map.of(
                  "sourceRun", source.id(), "rollbackRun", newId, "withdraw", previous.isEmpty()));
          return repo.run(newId);
        });
  }

  public Run reconcile(String id, ReconcileRequest request) {
    return repo.transaction(
        () -> {
          var run = run(id);
          access.require(run.projectId(), "task.execute");
          access.require(run.projectId(), "deployment.confirm");
          if (request == null
              || !Set.of("SUCCEEDED", "FAILED").contains(text(request.outcome()))
              || blank(request.evidenceRef())
              || blank(request.comment())
              || request.comment().trim().length() < 10)
            throw new ApiException(
                422, "EVIDENCE_REQUIRED", "核查需提供 SUCCEEDED/FAILED 结论、证据引用和至少 10 字说明");
          if (!"UNKNOWN".equals(run.status()))
            throw new ApiException(409, "NOT_UNKNOWN", "只可核查 UNKNOWN 执行");
          if ("SUCCEEDED".equals(request.outcome())) {
            if (!"SIMULATED".equals(run.mode()))
              throw new ApiException(503, "PROBE_NOT_CONFIGURED", "真实结果核查适配器尚未配置，不能仅凭台账说明标记真实执行成功");
            if (!repo.currentSimulation(run))
              throw new ApiException(409, "RESULT_NOT_FOUND", "未找到已提交的模拟输出，不能核查为成功；请记录失败结论后新建执行");
          }
          var evidence =
              Map.of(
                  "outcome",
                  request.outcome(),
                  "evidenceRef",
                  request.evidenceRef(),
                  "comment",
                  request.comment(),
                  "actor",
                  access.actor().subject());
          repo.reconcile(run, request.outcome(), evidence);
          audit.record(run.projectId(), "run.manually_reconciled", run.id(), evidence);
          return repo.run(id);
        });
  }

  List<AssetTarget> check(Task t, Actor actor) {
    var ids = strings(t.config().get("targetIds"));
    var targets = assets.requireTargets(t.projectId(), ids, true);
    if (targets.size() != ids.size())
      throw new ApiException(422, "TARGETS_INVALID", "目标不存在或不属于当前项目");
    if (targets.stream().anyMatch(a -> !t.mode().equals(a.mode())))
      throw new ApiException(422, "MODE_MISMATCH", "模拟任务与真实资产不得混用");
    if ("SIMULATED".equals(t.mode()) && !"demo".equals(t.projectId()))
      throw new ApiException(422, "SIMULATION_ISOLATION", "模拟执行只允许 demo 隔离项目");
    if ("CLUSTER".equals(t.kind()) && targets.stream().anyMatch(a -> !"SERVER".equals(a.type())))
      throw new ApiException(422, "HOST_REQUIRED", "集群节点必须是物理服务器");
    for (String dependency : strings(t.config().get("dependsOnTaskIds")))
      dependency(t, dependency, null);
    if ("VM".equals(t.kind())) {
      var cluster = dependency(t, text(t.config().get("clusterId")), "CLUSTER");
      if (!"PVE".equals(cluster.type()))
        throw new ApiException(422, "CLUSTER_TYPE_MISMATCH", "虚拟机任务必须使用已完成的 PVE 集群");
      if (!strings(cluster.config().get("physicalAssetIds"))
          .contains(text(t.config().get("hostId"))))
        throw new ApiException(422, "HOST_NOT_IN_CLUSTER", "宿主机不属于所选集群");
      var host =
          targets.stream()
              .filter(a -> a.id().equals(text(t.config().get("hostId"))))
              .findFirst()
              .orElseThrow(() -> new ApiException(422, "HOST_REQUIRED", "未找到宿主机"));
      if (!"SERVER".equals(host.type()))
        throw new ApiException(422, "HOST_REQUIRED", "宿主机必须为物理服务器");
      var templates = new HashSet<>(strings(host.technical().get("templates")));
      if ("SIMULATED".equals(t.mode()))
        templates.addAll(Set.of("ubuntu-24.04", "debian-12", "rocky-9", "openEuler-24.03"));
      if (!templates.contains(text(t.config().get("template"))))
        throw new ApiException(422, "TEMPLATE_NOT_FOUND", "所选模板未在宿主模板目录登记");
      repo.checkCapacity(t, targets);
    }
    if ("DEPLOYMENT".equals(t.kind())) {
      var environment = dependency(t, text(t.config().get("environment")), "ENVIRONMENT");
      if (!strings(environment.config().get("targetIds")).containsAll(ids))
        throw new ApiException(422, "ENVIRONMENT_TARGET_MISMATCH", "部署目标必须属于所选环境");
      Object baseline = t.config().get("rollbackBaseline");
      if (!empty(baseline)) {
        var run = repo.run(text(baseline));
        if (!run.projectId().equals(t.projectId())
            || !run.mode().equals(t.mode())
            || !"SUCCEEDED".equals(run.status())
            || !"DEPLOYMENT".equals(run.snapshot().get("kind")))
          throw new ApiException(422, "ROLLBACK_BASELINE_INVALID", "回退基线必须是同项目同模式的成功部署");
      }
    }
    if ("CONTAINER_PLATFORM".equals(t.type())
        && !Set.of("Kubernetes", "Docker Swarm").contains(text(t.config().get("platform"))))
      throw new ApiException(422, "PLATFORM_INVALID", "容器平台必须为 Kubernetes 或 Docker Swarm");
    return targets;
  }

  Task dependency(Task source, String id, String kind) {
    if (source.id().equals(id)) throw new ApiException(422, "DEPENDENCY_CYCLE", "任务不能依赖自身");
    Task dependency = repo.task(id, false);
    if (!source.projectId().equals(dependency.projectId())
        || !source.mode().equals(dependency.mode())
        || !"SUCCEEDED".equals(dependency.status())
        || (kind != null && !kind.equals(dependency.kind())))
      throw new ApiException(
          422, "DEPENDENCY_NOT_READY", "依赖必须为同项目、同模式且已成功完成的" + (kind == null ? "任务" : kind));
    return dependency;
  }

  boolean production(Task t, List<AssetTarget> targets) {
    if (Boolean.TRUE.equals(t.config().get("production"))
        || "PRODUCTION".equalsIgnoreCase(text(t.config().get("environmentTier")))) return true;
    if (targets.stream()
        .anyMatch(a -> "PRODUCTION".equalsIgnoreCase(text(a.technical().get("environmentTier")))))
      return true;
    if ("DEPLOYMENT".equals(t.kind())) {
      var env = dependency(t, text(t.config().get("environment")), "ENVIRONMENT");
      return Boolean.TRUE.equals(env.config().get("production"))
          || "PRODUCTION".equalsIgnoreCase(text(env.config().get("environmentTier")));
    }
    return false;
  }

  void configureSchedule(Task t) {
    var schedule = map(t.config().get("schedule"));
    boolean enabled = Boolean.TRUE.equals(schedule.get("enabled"));
    int minutes = 60;
    if (enabled) {
      var errors = errors(t.kind(), t.type(), t.config());
      if (!errors.isEmpty()) throw new ApiException(422, "VALIDATION", String.join("；", errors));
      var actor = access.serviceActor(text(schedule.get("serviceSubject")));
      access.authorizeSnapshot(actor, t.projectId(), "task.execute");
      minutes = (int) Double.parseDouble(text(schedule.get("intervalMinutes")));
    }
    repo.schedule(t.id(), enabled, minutes);
  }

  static void requireConfig(Task t) {
    var errors = errors(t.kind(), t.type(), t.config());
    if (!errors.isEmpty()) throw new ApiException(422, "VALIDATION", String.join("；", errors));
  }

  void verifyConfirmation(Task t, List<AssetTarget> targets) {
    var confirmed = repo.confirmedSnapshot(t.id());
    var frozenTargets = confirmed.get("targets") instanceof List<?> list ? list : List.of();
    if (!Objects.equals(map(confirmed.get("config")), t.config())
        || frozenTargets.size() != targets.size())
      throw new ApiException(409, "CONFIRMATION_STALE", "确认快照已失效，请重新确认");
    for (var target : targets) {
      var frozen =
          frozenTargets.stream()
              .map(TaskRules::map)
              .filter(m -> target.id().equals(m.get("id")))
              .findFirst()
              .orElse(Map.of());
      if (!String.valueOf(target.version()).equals(text(frozen.get("version"))))
        throw new ApiException(409, "CONFIRMATION_STALE", "目标版本在确认后发生变化，请重新确认");
    }
    var frozenDependencies = confirmed.getOrDefault("dependencies", List.of());
    if (!Objects.equals(frozenDependencies, dependencies(t)))
      throw new ApiException(409, "CONFIRMATION_STALE", "环境或集群依赖版本在确认后变化，请重新确认");
  }

  Map<String, Object> executionSnapshot(Task t, List<AssetTarget> targets) {
    var s = snapshot(t, targets);
    s.put("dependencies", dependencies(t));
    return s;
  }

  List<Map<String, Object>> dependencies(Task t) {
    var ids = new LinkedHashSet<>(strings(t.config().get("dependsOnTaskIds")));
    if ("VM".equals(t.kind())) ids.add(text(t.config().get("clusterId")));
    if ("DEPLOYMENT".equals(t.kind())) ids.add(text(t.config().get("environment")));
    return ids.stream()
        .filter(id -> !id.isBlank())
        .map(
            id -> {
              var dep = dependency(t, id, null);
              Map<String, Object> d = new LinkedHashMap<>();
              d.put("id", id);
              d.put("version", String.valueOf(dep.version()));
              d.put("runId", repo.lastSucceededRunId(id));
              return d;
            })
        .toList();
  }

  void verifyDependencies(Task t, Map<String, Object> snapshot) {
    if (!Objects.equals(snapshot.getOrDefault("dependencies", List.of()), dependencies(t)))
      throw new ApiException(409, "DEPENDENCY_CHANGED", "依赖执行或版本在排队后发生变化，请创建新执行");
  }

  static void version(Task t, Long v) {
    if (v == null) throw new ApiException(400, "VERSION_REQUIRED", "必须提交任务版本 version");
    if (v != t.version()) throw new ApiException(409, "VERSION_CONFLICT", "任务已变更，请刷新并重新确认");
  }

  static Map<String, Object> snapshot(Task t, List<AssetTarget> targets) {
    var result = new LinkedHashMap<String, Object>();
    result.put("taskId", t.id());
    result.put("name", t.name());
    result.put("kind", t.kind());
    result.put("type", t.type());
    result.put("mode", t.mode());
    result.put("projectId", t.projectId());
    result.put("version", t.version());
    result.put("config", t.config());
    result.put("targets", targetMaps(targets));
    return result;
  }

  static List<Map<String, Object>> targetMaps(List<AssetTarget> targets) {
    return targets.stream()
        .map(
            a -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", a.id());
              m.put("name", a.name());
              m.put("version", a.version());
              m.put("type", a.type());
              m.put("mode", a.mode());
              return m;
            })
        .toList();
  }

  static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static Actor actor(Map<String, Object> m) {
    return new Actor(
        text(m.get("subject")),
        new HashSet<>(strings(m.get("roles"))),
        new HashSet<>(strings(m.get("projects"))),
        new HashSet<>(strings(m.get("permissions"))),
        Boolean.TRUE.equals(m.get("service")));
  }
}
