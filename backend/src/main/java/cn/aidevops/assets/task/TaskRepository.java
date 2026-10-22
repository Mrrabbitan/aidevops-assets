package cn.aidevops.assets.task;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.common.Jsons;
import cn.aidevops.assets.task.TaskModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class TaskRepository {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public TaskRepository(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public <T> T transaction(java.util.function.Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  static Instant instant(ResultSet r, String key) throws SQLException {
    var v = r.getTimestamp(key);
    return v == null ? null : v.toInstant();
  }

  static final RowMapper<Task> TASK =
      (r, n) ->
          new Task(
              r.getString("id"),
              r.getString("project_id"),
              r.getString("name"),
              r.getString("kind"),
              r.getString("type"),
              r.getString("mode"),
              r.getLong("version"),
              r.getString("status"),
              Jsons.readMap(r.getString("config")),
              (Long) r.getObject("confirmed_version"),
              instant(r, "created_at"),
              instant(r, "updated_at"));

  public Task task(String id, boolean lock) {
    uuid(id);
    return db
        .query("SELECT * FROM tasks WHERE id=?::uuid" + (lock ? " FOR UPDATE" : ""), TASK, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "任务不存在"));
  }

  public List<Task> tasks(String project, String kind) {
    return kind == null || kind.isBlank()
        ? db.query(
            "SELECT * FROM tasks WHERE project_id=? ORDER BY created_at DESC LIMIT 500",
            TASK,
            project)
        : db.query(
            "SELECT * FROM tasks WHERE project_id=? AND kind=? ORDER BY created_at DESC LIMIT 500",
            TASK,
            project,
            kind);
  }

  public Task insert(SaveRequest r, String actor) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO tasks(id,project_id,name,kind,type,mode,config,created_by) VALUES"
            + " (?::uuid,?,?,?,?,?,?::jsonb,?)",
        id,
        r.projectId(),
        r.name().trim(),
        r.kind(),
        r.type(),
        r.mode(),
        Jsons.write(r.config()),
        actor);
    return task(id, false);
  }

  public Task update(Task old, SaveRequest r) {
    int count =
        db.update(
            "UPDATE tasks SET"
                + " name=?,type=?,mode=?,config=?::jsonb,version=version+1,status='DRAFT',confirmed_version=NULL,confirmed_snapshot=NULL,schedule_enabled=false,next_run_at=NULL,updated_at=now()"
                + " WHERE id=?::uuid AND version=?",
            r.name().trim(),
            r.type(),
            r.mode(),
            Jsons.write(r.config()),
            old.id(),
            old.version());
    if (count != 1) throw new ApiException(409, "VERSION_CONFLICT", "任务已修改，请刷新");
    return task(old.id(), false);
  }

  public void validated(String id) {
    db.update("UPDATE tasks SET status='VALIDATED',updated_at=now() WHERE id=?::uuid", id);
  }

  public void confirmed(Task task, Map<String, Object> snapshot) {
    db.update(
        "UPDATE tasks SET"
            + " confirmed_version=version,confirmed_snapshot=?::jsonb,status='CONFIRMED',updated_at=now()"
            + " WHERE id=?::uuid",
        Jsons.write(snapshot),
        task.id());
  }

  public Map<String, Object> confirmedSnapshot(String id) {
    return db
        .query(
            "SELECT confirmed_snapshot FROM tasks WHERE id=?::uuid",
            (r, n) -> Jsons.readMap(r.getString(1)),
            id)
        .stream()
        .findFirst()
        .orElse(Map.of());
  }

  public void schedule(String id, boolean enabled, int minutes) {
    db.update(
        "UPDATE tasks SET schedule_enabled=?,next_run_at=CASE WHEN ? THEN now()+ (? * interval '1"
            + " minute') ELSE NULL END WHERE id=?::uuid",
        enabled,
        enabled,
        minutes,
        id);
  }

  public List<Task> due() {
    return db.query(
        "SELECT * FROM tasks WHERE schedule_enabled AND next_run_at<=now() ORDER BY next_run_at"
            + " LIMIT 20",
        TASK);
  }

  /** Claim the current tick before dispatch. Older ticks are skipped instead of replayed. */
  public boolean scheduleTick(String id, int minutes) {
    var rows =
        db.query(
            "SELECT next_run_at FROM tasks WHERE id=?::uuid AND schedule_enabled AND"
                + " next_run_at<=now() FOR UPDATE SKIP LOCKED",
            (r, n) -> r.getTimestamp(1).toInstant(),
            id);
    if (rows.isEmpty()) return false;
    db.update(
        "UPDATE tasks SET next_run_at=now()+ (? * interval '1 minute') WHERE id=?::uuid",
        minutes,
        id);
    return rows.getFirst().isAfter(Instant.now().minusSeconds(60));
  }

  public boolean active(String taskId) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM task_runs WHERE task_id=?::uuid AND status IN"
                + " ('QUEUED','RUNNING','UNKNOWN'))",
            Boolean.class,
            taskId));
  }

  public boolean hasRuns(String taskId) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM task_runs WHERE task_id=?::uuid)", Boolean.class, taskId));
  }

  public void delete(String id) {
    db.update("DELETE FROM tasks WHERE id=?::uuid", id);
  }

  public Optional<Run> receipt(String project, String key, String hash) {
    var ids =
        db.query(
            "SELECT id,request_hash FROM task_runs WHERE project_id=? AND idempotency_key=?",
            (r, n) -> Map.of("id", r.getString(1), "hash", r.getString(2)),
            project,
            key);
    if (ids.isEmpty()) return Optional.empty();
    if (!ids.getFirst().get("hash").equals(hash))
      throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求");
    return Optional.of(run(ids.getFirst().get("id")));
  }

  public String enqueue(
      Task task,
      Map<String, Object> snapshot,
      Object actor,
      String key,
      String hash,
      List<Map<String, Object>> targets) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " task_runs(id,task_id,project_id,task_version,mode,snapshot,actor_snapshot,idempotency_key,request_hash)"
            + " VALUES (?::uuid,?::uuid,?,?,?,?::jsonb,?::jsonb,?,?)",
        id,
        task.id(),
        task.projectId(),
        task.version(),
        task.mode(),
        Jsons.write(snapshot),
        Jsons.write(actor),
        key,
        hash);
    for (var target : targets)
      db.update(
          "INSERT INTO task_run_targets(id,run_id,target_id,name) VALUES (?::uuid,?::uuid,?,?)",
          UUID.randomUUID().toString(),
          id,
          target.get("id"),
          target.get("name"));
    db.update("UPDATE tasks SET status='QUEUED',updated_at=now() WHERE id=?::uuid", task.id());
    log(id, null, "INFO", "执行已排队；配置、制品引用、目标版本和授权主体已冻结");
    return id;
  }

  public List<Run> runs(String project) {
    return db
        .query(
            "SELECT id FROM task_runs WHERE project_id=? ORDER BY created_at DESC LIMIT 200",
            (r, n) -> r.getString(1),
            project)
        .stream()
        .map(this::run)
        .toList();
  }

  public Run run(String id) {
    uuid(id);
    return db
        .query(
            "SELECT * FROM task_runs WHERE id=?::uuid",
            (r, n) ->
                new Run(
                    r.getString("id"),
                    r.getString("task_id"),
                    r.getString("project_id"),
                    r.getLong("task_version"),
                    r.getString("mode"),
                    r.getString("status"),
                    r.getInt("progress"),
                    Jsons.readMap(r.getString("snapshot")),
                    targets(id),
                    instant(r, "created_at"),
                    instant(r, "started_at"),
                    instant(r, "finished_at")),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "执行记录不存在"));
  }

  public List<RunTarget> targets(String id) {
    return db.query(
        "SELECT * FROM task_run_targets WHERE run_id=?::uuid ORDER BY target_id",
        (r, n) ->
            new RunTarget(
                r.getString("id"),
                r.getString("target_id"),
                r.getString("name"),
                r.getString("status"),
                Jsons.readMap(r.getString("result"))),
        id);
  }

  public List<Log> logs(String id) {
    return db.query(
        "SELECT * FROM task_run_logs WHERE run_id=?::uuid ORDER BY sequence LIMIT 2000",
        (r, n) ->
            new Log(
                r.getLong("sequence"),
                r.getString("target_id"),
                r.getString("level"),
                r.getString("message"),
                instant(r, "created_at")),
        id);
  }

  public void log(String runId, String targetId, String level, String message) {
    db.update(
        "INSERT INTO task_run_logs(run_id,target_id,level,message) VALUES (?::uuid,?,?,?)",
        runId,
        targetId,
        level,
        message);
  }

  public Optional<Lease> claim(String worker) {
    return transaction(
        () -> {
          var ids =
              db.query(
                  "SELECT id FROM task_runs WHERE status='QUEUED' ORDER BY created_at LIMIT 1 FOR"
                      + " UPDATE SKIP LOCKED",
                  (r, n) -> r.getString(1));
          if (ids.isEmpty()) return Optional.empty();
          String id = ids.getFirst();
          db.update(
              "UPDATE task_runs SET"
                  + " status='RUNNING',worker_id=?,fencing_token=fencing_token+1,lease_until=now()+interval"
                  + " '30 seconds',heartbeat_at=now(),started_at=now() WHERE id=?::uuid",
              worker,
              id);
          db.update(
              "UPDATE tasks SET status='RUNNING',updated_at=now() WHERE id=(SELECT task_id FROM"
                  + " task_runs WHERE id=?::uuid)",
              id);
          return db
              .query(
                  "SELECT fencing_token,actor_snapshot FROM task_runs WHERE id=?::uuid",
                  (r, n) -> new Lease(id, worker, r.getLong(1), Jsons.readMap(r.getString(2))),
                  id)
              .stream()
              .findFirst();
        });
  }

  public boolean heartbeat(Lease lease) {
    return db.update(
            "UPDATE task_runs SET heartbeat_at=now(),lease_until=now()+interval '30 seconds' WHERE"
                + " id=?::uuid AND worker_id=? AND fencing_token=? AND status='RUNNING' AND"
                + " lease_until>now()",
            lease.runId(),
            lease.workerId(),
            lease.fencingToken())
        == 1;
  }

  public void target(Lease lease, String targetId, String status, Map<String, Object> result) {
    int n =
        db.update(
            "UPDATE task_run_targets SET status=?,result=?::jsonb,updated_at=now() WHERE"
                + " run_id=?::uuid AND target_id=? AND EXISTS(SELECT 1 FROM task_runs WHERE"
                + " id=?::uuid AND worker_id=? AND fencing_token=? AND status='RUNNING' AND"
                + " lease_until>now())",
            status,
            Jsons.write(result),
            lease.runId(),
            targetId,
            lease.runId(),
            lease.workerId(),
            lease.fencingToken());
    if (n != 1) throw new ApiException(409, "LEASE_LOST", "执行租约失效；旧 worker 不能提交结果");
  }

  public void complete(Lease lease, String status) {
    transaction(
        () -> {
          int n =
              db.update(
                  "UPDATE task_runs SET status=?,progress=CASE WHEN ?='SUCCEEDED' THEN 100 ELSE"
                      + " progress END,finished_at=now(),lease_until=NULL WHERE id=?::uuid AND"
                      + " worker_id=? AND fencing_token=? AND status='RUNNING' AND"
                      + " lease_until>now()",
                  status,
                  status,
                  lease.runId(),
                  lease.workerId(),
                  lease.fencingToken());
          if (n != 1) throw new ApiException(409, "LEASE_LOST", "执行租约失效");
          db.update(
              "UPDATE tasks SET status=?,updated_at=now() WHERE id=(SELECT task_id FROM task_runs"
                  + " WHERE id=?::uuid)",
              status,
              lease.runId());
          log(lease.runId(), null, "SUCCEEDED".equals(status) ? "INFO" : "ERROR", "执行结束：" + status);
          return null;
        });
  }

  public int recoverExpired() {
    return transaction(
        () -> {
          var ids =
              db.query(
                  "UPDATE task_runs SET"
                      + " status='UNKNOWN',fencing_token=fencing_token+1,finished_at=now(),lease_until=NULL"
                      + " WHERE status='RUNNING' AND lease_until<now() RETURNING id",
                  (r, n) -> r.getString(1));
          for (String id : ids) {
            db.update(
                "UPDATE tasks SET status='UNKNOWN',updated_at=now() WHERE id=(SELECT task_id FROM"
                    + " task_runs WHERE id=?::uuid)",
                id);
            db.update(
                "UPDATE task_run_targets SET status='UNKNOWN',updated_at=now() WHERE run_id=?::uuid"
                    + " AND status IN ('PENDING','RUNNING')",
                id);
            log(id, null, "WARN", "worker 租约超时，执行结果未知；已停止自动重放，需核查外部事实");
          }
          return ids.size();
        });
  }

  public void progress(Lease lease, int progress) {
    db.update(
        "UPDATE task_runs SET progress=? WHERE id=?::uuid AND worker_id=? AND fencing_token=? AND"
            + " status='RUNNING' AND lease_until>now()",
        progress,
        lease.runId(),
        lease.workerId(),
        lease.fencingToken());
  }

  public void checkCapacity(Task task, List<cn.aidevops.assets.common.AssetTarget> targets) {
    String host = TaskRules.text(task.config().get("hostId"));
    db.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (r, n) -> true, "capacity:" + host);
    var asset =
        targets.stream()
            .filter(a -> a.id().equals(host))
            .findFirst()
            .orElseThrow(() -> new ApiException(422, "HOST_REQUIRED", "宿主机未选择"));
    var used =
        db.queryForMap(
            "SELECT COALESCE(sum(cpu),0) cpu, COALESCE(sum(memory_gb),0)"
                + " memory,COALESCE(sum(disk_gb),0) disk FROM task_simulated_resources WHERE"
                + " host_id=? AND task_id<>?::uuid",
            host,
            task.id());
    var queued =
        db.queryForMap(
            "SELECT COALESCE(sum((snapshot->'config'->>'cpu')::numeric),0)"
                + " cpu,COALESCE(sum((snapshot->'config'->>'memoryGb')::numeric),0)"
                + " memory,COALESCE(sum((snapshot->'config'->>'diskGb')::numeric),0) disk FROM"
                + " task_runs WHERE status IN ('QUEUED','RUNNING','UNKNOWN') AND task_id<>?::uuid"
                + " AND snapshot->>'kind'='VM' AND snapshot->'config'->>'hostId'=?",
            task.id(),
            host);
    var tech = asset.technical();
    capacity(
        task,
        "cpu",
        tech.getOrDefault("cpuCores", tech.get("cpu")),
        tech.get("reservedCpu"),
        used.get("cpu"),
        queued.get("cpu"));
    capacity(
        task,
        "memoryGb",
        tech.get("memoryGb"),
        tech.get("reservedMemoryGb"),
        used.get("memory"),
        queued.get("memory"));
    capacity(
        task,
        "diskGb",
        tech.get("diskGb"),
        tech.get("reservedDiskGb"),
        used.get("disk"),
        queued.get("disk"));
  }

  private static double number(Object v) {
    if (v == null) return 0;
    try {
      return Double.parseDouble(String.valueOf(v));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static void capacity(
      Task t, String key, Object total, Object reserved, Object used, Object queued) {
    double request = number(t.config().get(key));
    double available = number(total) - number(reserved) - number(used) - number(queued);
    if (request > available)
      throw new ApiException(
          422, "CAPACITY_EXCEEDED", key + " 容量不足：可用 " + available + "，申请 " + request);
  }

  public void simulation(Lease lease, Run run) {
    transaction(
        () -> {
          if (!heartbeat(lease)) throw new ApiException(409, "LEASE_LOST", "执行租约失效");
          if (!"SIMULATED".equals(run.mode()) || !"demo".equals(run.projectId()))
            throw new ApiException(422, "SIMULATION_ISOLATION", "模拟结果仅可写入 demo 隔离项目");
          var c = TaskRules.map(run.snapshot().get("config"));
          var state = new LinkedHashMap<String, Object>();
          state.put("mode", "SIMULATED");
          state.put("observed", false);
          state.put("config", c);
          state.put("runId", run.id());
          if (Boolean.TRUE.equals(run.snapshot().get("rollback"))) {
            state.put("config", run.snapshot().get("restoreConfig"));
            state.put("withdrawn", Boolean.TRUE.equals(run.snapshot().get("withdraw")));
          }
          db.update(
              "INSERT INTO"
                  + " task_simulated_resources(id,project_id,task_id,kind,host_id,cpu,memory_gb,disk_gb,current_run_id,state)"
                  + " VALUES (?::uuid,?,?::uuid,?,?,?,?,?,?::uuid,?::jsonb) ON CONFLICT(task_id) DO"
                  + " UPDATE SET"
                  + " previous_run_id=task_simulated_resources.current_run_id,current_run_id=excluded.current_run_id,state=excluded.state,host_id=excluded.host_id,cpu=excluded.cpu,memory_gb=excluded.memory_gb,disk_gb=excluded.disk_gb,updated_at=now()",
              UUID.randomUUID().toString(),
              run.projectId(),
              run.taskId(),
              TaskRules.text(run.snapshot().get("kind")),
              TaskRules.text(c.get("hostId")),
              (int) number(c.get("cpu")),
              (int) number(c.get("memoryGb")),
              (int) number(c.get("diskGb")),
              run.id(),
              Jsons.write(state));
          return null;
        });
  }

  public Optional<Run> previousDeployment(Run run) {
    return db
        .query(
            "SELECT previous_run_id FROM task_simulated_resources WHERE task_id=?::uuid AND"
                + " current_run_id=?::uuid AND previous_run_id IS NOT NULL",
            (r, n) -> r.getString(1),
            run.taskId(),
            run.id())
        .stream()
        .findFirst()
        .map(this::run)
        .filter(r -> !Boolean.TRUE.equals(r.snapshot().get("withdraw")));
  }

  public boolean currentSimulation(Run run) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM task_simulated_resources WHERE task_id=?::uuid AND"
                + " current_run_id=?::uuid)",
            Boolean.class,
            run.taskId(),
            run.id()));
  }

  public String lastSucceededRunId(String taskId) {
    return db
        .query(
            "SELECT id FROM task_runs WHERE task_id=?::uuid AND status='SUCCEEDED' ORDER BY"
                + " created_at DESC,id DESC LIMIT 1",
            (r, n) -> r.getString(1),
            taskId)
        .stream()
        .findFirst()
        .orElse("");
  }

  public void reconcile(Run run, String outcome, Object evidence) {
    if (db.update(
            "UPDATE task_runs SET status=?,progress=100,finished_at=now() WHERE id=?::uuid AND"
                + " status='UNKNOWN'",
            outcome,
            run.id())
        != 1) throw new ApiException(409, "NOT_UNKNOWN", "只可核查 UNKNOWN 执行");
    db.update("UPDATE tasks SET status=?,updated_at=now() WHERE id=?::uuid", outcome, run.taskId());
    db.update(
        "UPDATE task_run_targets SET"
            + " status=?,result=jsonb_build_object('manualReconciliation',true,'evidence',?::jsonb),updated_at=now()"
            + " WHERE run_id=?::uuid AND status='UNKNOWN'",
        outcome,
        Jsons.write(evidence),
        run.id());
    log(run.id(), null, "WARN", "已记录人工核查结论：" + outcome + "；未触发外部操作，也未凭此修改部署实况");
  }

  static void uuid(String id) {
    try {
      UUID.fromString(id);
    } catch (RuntimeException ex) {
      throw new ApiException(404, "NOT_FOUND", "记录不存在");
    }
  }
}
