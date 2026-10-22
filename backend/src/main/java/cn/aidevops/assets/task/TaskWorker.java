package cn.aidevops.assets.task;

import static cn.aidevops.assets.task.TaskRules.*;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import cn.aidevops.assets.task.TaskModels.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "assets.role", havingValue = "worker")
public class TaskWorker {
  private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
  private final String workerId = UUID.randomUUID().toString();
  private final TaskRepository repo;
  private final TaskService tasks;
  private final Access access;
  private final AssetsPort assets;
  private final Audit audit;
  private final List<TaskExecutionAdapter> adapters;

  public TaskWorker(
      TaskRepository repo,
      TaskService tasks,
      Access access,
      AssetsPort assets,
      Audit audit,
      List<TaskExecutionAdapter> adapters) {
    this.repo = repo;
    this.tasks = tasks;
    this.access = access;
    this.assets = assets;
    this.audit = audit;
    this.adapters = adapters;
  }

  @Scheduled(fixedDelayString = "${assets.worker.poll-ms:1000}")
  public void poll() {
    try {
      repo.recoverExpired();
      for (int i = 0; i < 4; i++) {
        var lease = repo.claim(workerId);
        if (lease.isEmpty()) return;
        perform(lease.get());
      }
    } catch (Exception e) {
      log.error("Task polling failed; queue remains durable", e);
    }
  }

  void perform(Lease lease) {
    access.runAs(
        TaskService.actor(lease.actor()),
        () -> {
          performAuthorized(lease);
          return null;
        });
  }

  private void performAuthorized(Lease lease) {
    Run run = repo.run(lease.runId());
    boolean sideEffectStarted = false;
    try {
      var actor = TaskService.actor(lease.actor());
      access.authorizeSnapshot(actor, run.projectId(), "task.execute");
      Task frozen = frozen(run);
      TaskService.requireConfig(frozen);
      var targets = tasks.check(frozen, actor);
      tasks.verifyDependencies(frozen, run.snapshot());
      boolean confirmRequired =
          tasks.production(frozen, targets) || Boolean.TRUE.equals(run.snapshot().get("rollback"));
      if (confirmRequired) access.authorizeSnapshot(actor, run.projectId(), "deployment.confirm");
      var frozenTargets = run.snapshot().get("targets") instanceof List<?> list ? list : List.of();
      for (var target : targets) {
        var frozenTarget =
            frozenTargets.stream()
                .map(TaskRules::map)
                .filter(m -> target.id().equals(m.get("id")))
                .findFirst()
                .orElseThrow(() -> new ApiException(409, "TARGET_CHANGED", "冻结目标缺失"));
        if (!(frozenTarget.get("version") instanceof Number version)
            || version.longValue() != target.version())
          throw new ApiException(409, "TARGET_CHANGED", "目标版本已变化，请创建新执行");
      }
      var adapter =
          adapters.stream()
              .filter(a -> a.supports(run.mode()))
              .findFirst()
              .orElseThrow(() -> new ApiException(503, "NOT_CONFIGURED", "没有可用的执行适配器"));
      repo.log(run.id(), null, "INFO", "授权与目标版本复核通过；执行模式 " + run.mode());
      int done = 0;
      for (var target : run.targets()) {
        // Every target is separately authorized; stopping a service grant prevents following
        // targets.
        access.authorizeSnapshot(actor, run.projectId(), "task.execute");
        if (confirmRequired) access.authorizeSnapshot(actor, run.projectId(), "deployment.confirm");
        if (!repo.heartbeat(lease)) throw new ApiException(409, "LEASE_LOST", "租约失效，停止后续目标");
        repo.target(lease, target.targetId(), "RUNNING", Map.of("mode", run.mode()));
        repo.log(
            run.id(), target.targetId(), "INFO", "开始目标：" + target.name() + " [" + run.mode() + "]");
        sideEffectStarted = true;
        var result = adapter.execute(run, target);
        if (!repo.heartbeat(lease)) throw new ApiException(409, "LEASE_LOST", "目标执行后租约失效，结果待核查");
        repo.target(lease, target.targetId(), "SUCCEEDED", result);
        repo.log(run.id(), target.targetId(), "INFO", text(result.get("message")));
        repo.progress(lease, (++done * 90) / Math.max(1, run.targets().size()));
      }
      // Only the isolated simulation store changes. Real adapters cannot claim success by ledger
      // writes.
      if ("SIMULATED".equals(run.mode()))
        repo.transaction(
            () -> {
              repo.simulation(lease, run);
              repo.complete(lease, "SUCCEEDED");
              return null;
            });
      else repo.complete(lease, "SUCCEEDED");
      audit.record(
          run.projectId(),
          "run.completed",
          run.id(),
          Map.of("mode", run.mode(), "authorizedSubject", actor.subject(), "status", "SUCCEEDED"));
    } catch (Exception e) {
      String status =
          sideEffectStarted && !"SIMULATED".equals(run.mode())
              ? "UNKNOWN"
              : repo.targets(run.id()).stream().anyMatch(t -> "SUCCEEDED".equals(t.status()))
                  ? "PARTIAL_FAILED"
                  : "FAILED";
      if (e instanceof ApiException api && "LEASE_LOST".equals(api.code)) {
        log.warn("Worker lost fencing lease for run {}", run.id());
        return;
      }
      try {
        repo.log(
            run.id(),
            null,
            "ERROR",
            e instanceof ApiException api
                ? api.code + ": " + api.getMessage()
                : "执行异常，详情请按执行 ID 核查服务日志");
        for (var target : repo.targets(run.id()))
          if (Set.of("RUNNING", "PENDING").contains(target.status()))
            repo.target(
                lease,
                target.targetId(),
                "PENDING".equals(target.status())
                    ? "SKIPPED"
                    : "UNKNOWN".equals(status) ? "UNKNOWN" : "FAILED",
                Map.of(
                    "mode",
                    run.mode(),
                    "code",
                    e instanceof ApiException api ? api.code : "EXECUTION_FAILED"));
        repo.complete(lease, status);
        audit.record(
            run.projectId(), "run.failed", run.id(), Map.of("status", status, "mode", run.mode()));
      } catch (Exception report) {
        log.error(
            "Unable to persist run failure {}; lease recovery will mark UNKNOWN", run.id(), report);
      }
    }
  }

  private Task frozen(Run run) {
    var s = run.snapshot();
    var current = repo.task(run.taskId(), false);
    if (current.version() != run.taskVersion())
      throw new ApiException(409, "VERSION_CONFLICT", "任务版本与执行快照不一致");
    return new Task(
        run.taskId(),
        run.projectId(),
        text(s.get("name")),
        text(s.get("kind")),
        text(s.get("type")),
        run.mode(),
        run.taskVersion(),
        current.status(),
        map(s.get("config")),
        current.confirmedVersion(),
        current.createdAt(),
        current.updatedAt());
  }

  @Scheduled(fixedDelayString = "${assets.worker.schedule-poll-ms:10000}")
  public void schedules() {
    try {
      for (var task : repo.due()) {
        var config = map(task.config().get("schedule"));
        int minutes = (int) Double.parseDouble(text(config.getOrDefault("intervalMinutes", 60)));
        boolean current = repo.transaction(() -> repo.scheduleTick(task.id(), minutes));
        if (!current) {
          audit.record(
              task.projectId(),
              "schedule.missed_tick",
              task.id(),
              Map.of("policy", "SKIP_BACKLOG"));
          continue;
        }
        try {
          var actor = access.serviceActor(text(config.get("serviceSubject")));
          access.authorizeSnapshot(actor, task.projectId(), "task.execute");
          access.runAs(
              actor,
              () ->
                  tasks.enqueue(
                      task.id(),
                      new ExecuteRequest(
                          task.version(),
                          "schedule:" + task.id() + ":" + Instant.now().toEpochMilli()),
                      actor,
                      true));
        } catch (ApiException e) {
          audit.record(
              task.projectId(),
              "schedule.rejected",
              task.id(),
              Map.of("code", e.code, "serviceSubject", text(config.get("serviceSubject"))));
        }
      }
    } catch (Exception e) {
      log.error("Task scheduling failed", e);
    }
  }
}
