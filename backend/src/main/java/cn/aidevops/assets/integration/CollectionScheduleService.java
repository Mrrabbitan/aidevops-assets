package cn.aidevops.assets.integration;

import static cn.aidevops.assets.integration.CollectionScheduleRules.*;

import cn.aidevops.assets.asset.AssetService;
import cn.aidevops.assets.common.*;
import cn.aidevops.assets.integration.CollectionScheduleModels.*;
import cn.aidevops.assets.security.Access;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CollectionScheduleService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Access access;
  private final AssetsPort targets;
  private final AssetService assets;
  private final Audit audit;

  public CollectionScheduleService(
      JdbcTemplate db,
      TransactionTemplate tx,
      Access access,
      AssetsPort targets,
      AssetService assets,
      Audit audit) {
    this.db = db;
    this.tx = tx;
    this.access = access;
    this.targets = targets;
    this.assets = assets;
    this.audit = audit;
  }

  private <T> T transaction(java.util.function.Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  private static Instant instant(ResultSet r, String key) throws SQLException {
    var timestamp = r.getTimestamp(key);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static final RowMapper<Plan> PLAN =
      (r, n) ->
          new Plan(
              r.getString("id"),
              r.getString("project_id"),
              r.getString("name"),
              r.getString("kind"),
              r.getString("mode"),
              r.getLong("version"),
              r.getBoolean("enabled"),
              r.getInt("interval_minutes"),
              r.getString("timezone"),
              r.getString("service_subject"),
              Jsons.readMap(r.getString("config")),
              instant(r, "next_run_at"),
              instant(r, "created_at"),
              instant(r, "updated_at"));
  private static final RowMapper<Run> RUN =
      (r, n) ->
          new Run(
              r.getString("id"),
              r.getString("schedule_id"),
              r.getString("project_id"),
              r.getString("kind"),
              r.getString("mode"),
              r.getLong("schedule_version"),
              r.getString("status"),
              r.getString("actor"),
              r.getString("requested_by"),
              Jsons.readMap(r.getString("result")),
              r.getString("preview_id"),
              r.getString("error_code"),
              r.getString("error_message"),
              instant(r, "created_at"),
              instant(r, "started_at"),
              instant(r, "finished_at"));

  public List<Plan> list(String project) {
    access.require(project, "asset.read");
    return db.query(
        "SELECT * FROM collection_schedules WHERE project_id=? ORDER BY updated_at DESC LIMIT 500",
        PLAN,
        project);
  }

  public List<Run> runs(String project) {
    access.require(project, "asset.read");
    return db.query(
        "SELECT * FROM collection_runs WHERE project_id=? ORDER BY created_at DESC LIMIT 200",
        RUN,
        project);
  }

  private Plan plan(String id, boolean lock) {
    uuid(id);
    return db
        .query(
            "SELECT * FROM collection_schedules WHERE id=?::uuid" + (lock ? " FOR UPDATE" : ""),
            PLAN,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "采集计划不存在"));
  }

  private Run run(String id) {
    return db.query("SELECT * FROM collection_runs WHERE id=?::uuid", RUN, id).stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "采集执行不存在"));
  }

  private void permission(String project) {
    access.require(project, "asset.read");
    access.require(project, "asset.execute");
  }

  private Access.Actor service(Plan p) {
    var actor = access.serviceActor(p.serviceSubject());
    access.authorizeSnapshot(actor, p.projectId(), "asset.read");
    access.authorizeSnapshot(actor, p.projectId(), "asset.execute");
    return actor;
  }

  private void scope(Plan p) {
    if ("SYNC".equals(p.kind())) {
      var ids = ids(p.config().get("assetIds"));
      var selected = targets.requireTargets(p.projectId(), ids, false);
      if (selected.size() != ids.size()
          || selected.stream().anyMatch(t -> !p.mode().equals(t.mode())))
        throw new ApiException(422, "SCOPE_INVALID", "同步范围含无权、失效或模式不一致的资产");
    }
  }

  public Plan save(String id, Save request) {
    Save r = normalize(request);
    permission(r.projectId());
    return transaction(
        () -> {
          if (id != null) {
            var old = plan(id, true);
            if (!old.projectId().equals(r.projectId()))
              throw new ApiException(403, "PROJECT_IMMUTABLE", "采集计划不能转移项目");
            version(old, r.version());
            if (active(id)) throw new ApiException(409, "ACTIVE_RUN", "采集计划存在活动或结果未知的执行，不能修改");
          }
          Plan draft =
              new Plan(
                  id,
                  r.projectId(),
                  r.name(),
                  r.kind(),
                  r.mode(),
                  1,
                  Boolean.TRUE.equals(r.enabled()),
                  r.intervalMinutes(),
                  r.timezone(),
                  r.serviceSubject(),
                  r.config(),
                  null,
                  null,
                  null);
          // Even disabled plans need an explicitly authorized service and a bounded collection
          // scope.
          service(draft);
          scope(draft);
          String actor = access.actor().subject();
          String selected = id == null ? UUID.randomUUID().toString() : id;
          if (id == null)
            db.update(
                "INSERT INTO"
                    + " collection_schedules(id,project_id,name,kind,mode,enabled,interval_minutes,timezone,service_subject,config,scope_approved_by,next_run_at)"
                    + " VALUES (?::uuid,?,?,?,?,?,?,?,?,?::jsonb,?,CASE WHEN ? THEN now()+ (? *"
                    + " interval '1 minute') ELSE NULL END)",
                selected,
                r.projectId(),
                r.name(),
                r.kind(),
                r.mode(),
                r.enabled(),
                r.intervalMinutes(),
                r.timezone(),
                r.serviceSubject(),
                Jsons.write(r.config()),
                actor,
                r.enabled(),
                r.intervalMinutes());
          else
            db.update(
                "UPDATE collection_schedules SET"
                    + " name=?,kind=?,mode=?,version=version+1,enabled=?,interval_minutes=?,timezone=?,service_subject=?,config=?::jsonb,scope_approved_by=?,next_run_at=CASE"
                    + " WHEN ? THEN now()+ (? * interval '1 minute') ELSE NULL END,updated_at=now()"
                    + " WHERE id=?::uuid",
                r.name(),
                r.kind(),
                r.mode(),
                r.enabled(),
                r.intervalMinutes(),
                r.timezone(),
                r.serviceSubject(),
                Jsons.write(r.config()),
                actor,
                r.enabled(),
                r.intervalMinutes(),
                selected);
          audit.record(
              r.projectId(),
              "collection.scope.approved",
              selected,
              Map.of(
                  "kind",
                  r.kind(),
                  "mode",
                  r.mode(),
                  "scope",
                  r.config(),
                  "serviceSubject",
                  r.serviceSubject(),
                  "enabled",
                  r.enabled()));
          return plan(selected, false);
        });
  }

  public Run execute(String id, Long version) {
    var p = plan(id, false);
    permission(p.projectId());
    String requestedBy = access.actor().subject();
    Run result =
        transaction(
            () -> {
              var current = plan(id, true);
              version(current, version);
              if (active(id)) throw new ApiException(409, "ACTIVE_RUN", "已有活动或结果未知的采集，禁止自动重放");
              if ("REAL".equals(current.mode()))
                return insertRun(
                    current, "FAILED", requestedBy, "NOT_CONFIGURED", "真实采集适配器尚未配置；未扫描或同步真实资产");
              service(current);
              scope(current);
              return insertRun(current, "QUEUED", requestedBy, null, null);
            });
    audit.record(
        p.projectId(),
        "collection.manual.requested",
        id,
        Map.of("runId", result.id(), "mode", p.mode()));
    if ("REAL".equals(p.mode()))
      throw new ApiException(503, "NOT_CONFIGURED", "真实采集适配器尚未配置；已保存失败回执，未执行真实采集");
    return result;
  }

  private boolean active(String id) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM collection_runs WHERE schedule_id=?::uuid AND status IN"
                + " ('QUEUED','RUNNING','UNKNOWN'))",
            Boolean.class,
            id));
  }

  private Run insertRun(Plan p, String status, String requester, String code, String error) {
    String id = UUID.randomUUID().toString();
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("projectId", p.projectId());
    snapshot.put("kind", p.kind());
    snapshot.put("mode", p.mode());
    snapshot.put("version", p.version());
    snapshot.put("serviceSubject", p.serviceSubject());
    snapshot.put("config", p.config());
    db.update(
        "INSERT INTO"
            + " collection_runs(id,schedule_id,project_id,kind,mode,schedule_version,snapshot,status,actor,requested_by,error_code,error_message,finished_at)"
            + " VALUES (?::uuid,?::uuid,?,?,?,?,?::jsonb,?,?,?,?,?,CASE WHEN ? IN"
            + " ('FAILED','SKIPPED') THEN now() ELSE NULL END)",
        id,
        p.id(),
        p.projectId(),
        p.kind(),
        p.mode(),
        p.version(),
        Jsons.write(snapshot),
        status,
        p.serviceSubject(),
        requester,
        code,
        error,
        status);
    return run(id);
  }

  /** Atomic due tick advances from now. Missed intervals never create catch-up jobs. */
  public void enqueueDue() {
    transaction(
        () -> {
          for (var p :
              db.query(
                  "SELECT * FROM collection_schedules WHERE enabled AND next_run_at<=now() ORDER BY"
                      + " next_run_at LIMIT 20 FOR UPDATE SKIP LOCKED",
                  PLAN)) {
            db.update(
                "UPDATE collection_schedules SET next_run_at=now()+ (? * interval '1 minute') WHERE"
                    + " id=?::uuid",
                p.intervalMinutes(),
                p.id());
            if (active(p.id())) continue;
            if (p.nextRunAt().isBefore(Instant.now().minusSeconds(60))) {
              insertRun(p, "SKIPPED", "scheduler", "MISSED_TICK", "跳过停机期间的积压采集；等待下一个正常周期");
              continue;
            }
            insertRun(p, "QUEUED", "scheduler", null, null);
          }
          return null;
        });
  }

  public Optional<Claim> claim(String worker) {
    return transaction(
        () -> {
          var rows =
              db.query(
                  "SELECT id FROM collection_runs WHERE status='QUEUED' ORDER BY created_at LIMIT 1"
                      + " FOR UPDATE SKIP LOCKED",
                  (r, n) -> r.getString(1));
          if (rows.isEmpty()) return Optional.empty();
          String id = rows.getFirst();
          db.update(
              "UPDATE collection_runs SET"
                  + " status='RUNNING',worker_id=?,fencing_token=fencing_token+1,lease_until=now()+interval"
                  + " '60 seconds',started_at=now() WHERE id=?::uuid",
              worker,
              id);
          return db
              .query(
                  "SELECT fencing_token,snapshot FROM collection_runs WHERE id=?::uuid",
                  (r, n) -> new Claim(id, worker, r.getLong(1), Jsons.readMap(r.getString(2))),
                  id)
              .stream()
              .findFirst();
        });
  }

  public void perform(Claim claim) {
    Run current = run(claim.id());
    try {
      var p = plan(current.scheduleId(), false);
      if (p.version() != current.scheduleVersion())
        throw new ApiException(409, "VERSION_CONFLICT", "采集范围或服务身份已变化，执行拒绝");
      var actor = service(p);
      access.runAs(
          actor,
          () ->
              transaction(
                  () -> {
                    if (!heartbeat(claim)) throw new ApiException(409, "LEASE_LOST", "采集执行租约失效");
                    if (!"SIMULATED".equals(p.mode()))
                      throw new ApiException(503, "NOT_CONFIGURED", "真实采集适配器尚未配置");
                    normalize(
                        new Save(
                            p.projectId(),
                            p.name(),
                            p.kind(),
                            p.mode(),
                            p.version(),
                            false,
                            p.intervalMinutes(),
                            p.timezone(),
                            p.serviceSubject(),
                            p.config()));
                    scope(p);
                    Map<String, Object> input = new LinkedHashMap<>(p.config());
                    input.put("projectId", p.projectId());
                    input.put("mode", p.mode());
                    // Sync returns a pending preview only. Applying it remains a separate
                    // authorized human action.
                    Map<String, Object> result =
                        "DISCOVERY".equals(p.kind())
                            ? assets.discover(input)
                            : assets.syncPreview(input);
                    String status = "SYNC".equals(p.kind()) ? "PREVIEW_READY" : "SUCCEEDED";
                    String preview = text(result.get("previewId"));
                    if (db.update(
                            "UPDATE collection_runs SET"
                                + " status=?,result=?::jsonb,preview_id=?,finished_at=now(),lease_until=NULL"
                                + " WHERE id=?::uuid AND status='RUNNING' AND worker_id=? AND"
                                + " fencing_token=? AND lease_until>now()",
                            status,
                            Jsons.write(result),
                            preview.isBlank() ? null : preview,
                            claim.id(),
                            claim.workerId(),
                            claim.fencingToken())
                        != 1) throw new ApiException(409, "LEASE_LOST", "采集结果租约失效，事务已回退");
                    audit.record(
                        p.projectId(),
                        "collection.completed",
                        claim.id(),
                        Map.of(
                            "status",
                            status,
                            "mode",
                            p.mode(),
                            "serviceSubject",
                            actor.subject(),
                            "scope",
                            p.config()));
                    return null;
                  }));
    } catch (Exception e) {
      String code = e instanceof ApiException a ? a.code : "COLLECTION_FAILED";
      String message = e instanceof ApiException a ? a.getMessage() : "采集处理失败，请按执行编号核查服务日志";
      db.update(
          "UPDATE collection_runs SET"
              + " status='FAILED',error_code=?,error_message=?,finished_at=now(),lease_until=NULL"
              + " WHERE id=?::uuid AND status='RUNNING' AND worker_id=? AND fencing_token=? AND"
              + " lease_until>now()",
          code,
          message,
          claim.id(),
          claim.workerId(),
          claim.fencingToken());
      audit.record(
          current.projectId(),
          "collection.failed",
          current.id(),
          Map.of("code", code, "mode", current.mode()));
    }
  }

  private boolean heartbeat(Claim c) {
    return db.update(
            "UPDATE collection_runs SET lease_until=now()+interval '60 seconds' WHERE id=?::uuid"
                + " AND status='RUNNING' AND worker_id=? AND fencing_token=? AND lease_until>now()",
            c.id(),
            c.workerId(),
            c.fencingToken())
        == 1;
  }

  public void recover() {
    db.update(
        "UPDATE collection_runs SET"
            + " status='UNKNOWN',fencing_token=fencing_token+1,error_code='LEASE_EXPIRED',error_message='采集进程租约超时；停止自动重放，需人工核查',finished_at=now(),lease_until=NULL"
            + " WHERE status='RUNNING' AND lease_until<now()");
  }

  public Run resolve(String id, Resolve request) {
    uuid(id);
    return transaction(
        () -> {
          var current = run(id);
          permission(current.projectId());
          if (!"UNKNOWN".equals(current.status()))
            throw new ApiException(409, "NOT_UNKNOWN", "只允许关闭结果未知的采集");
          if (request == null
              || blank(request.evidenceRef())
              || blank(request.comment())
              || request.comment().length() < 10)
            throw new ApiException(422, "EVIDENCE_REQUIRED", "请提供核查证据引用及至少 10 字说明");
          if (db.update(
                  "UPDATE collection_runs SET"
                      + " status='CLOSED_UNKNOWN',result=jsonb_build_object('evidenceRef',?,'comment',?,'closedBy',?),finished_at=now()"
                      + " WHERE id=?::uuid AND status='UNKNOWN'",
                  request.evidenceRef(),
                  request.comment(),
                  access.actor().subject(),
                  id)
              != 1) throw new ApiException(409, "VERSION_CONFLICT", "核查状态已变化");
          audit.record(
              current.projectId(),
              "collection.unknown.closed",
              id,
              Map.of(
                  "evidenceRef",
                  request.evidenceRef(),
                  "comment",
                  request.comment(),
                  "outcome",
                  "UNKNOWN",
                  "automaticReplay",
                  false));
          return run(id);
        });
  }

  private static void version(Plan p, Long v) {
    if (v == null) throw new ApiException(400, "VERSION_REQUIRED", "必须提交 version");
    if (v != p.version()) throw new ApiException(409, "VERSION_CONFLICT", "计划已变化，请刷新");
  }

  private static void uuid(String id) {
    try {
      UUID.fromString(id);
    } catch (RuntimeException e) {
      throw new ApiException(404, "NOT_FOUND", "采集计划不存在");
    }
  }
}
