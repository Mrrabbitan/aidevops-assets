package cn.aidevops.assets.alert;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
  private final JdbcTemplate jdbc;
  private final Access access;
  private final Audit audit;
  private final AssetsPort assets;

  public AlertService(JdbcTemplate j, Access a, Audit u, AssetsPort s) {
    jdbc = j;
    access = a;
    audit = u;
    assets = s;
  }

  public static String text(Map<String, Object> b, String k, String fallback) {
    return Objects.toString(b.get(k), fallback);
  }

  public static double num(Map<String, Object> b, String k, double fallback) {
    Object n = b.get(k);
    return n instanceof Number v ? v.doubleValue() : fallback;
  }

  public static Map<String, Object> view(Map<String, Object> row) {
    var m = new LinkedHashMap<String, Object>();
    row.forEach(
        (k, v) -> {
          StringBuilder key = new StringBuilder();
          boolean upper = false;
          for (char c : k.toCharArray()) {
            if (c == '_') {
              upper = true;
              continue;
            }
            key.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
          }
          String name = key.toString();
          if (v != null
              && Set.of("targetIds", "notificationChannels", "metrics").contains(name)
              && v instanceof String s) {
            m.put(name, Jsons.MAPPER.readValue(s, Object.class));
          } else m.put(name, v);
        });
    return m;
  }

  public Object rules(String p) {
    access.require(p, "alarm.read");
    return jdbc
        .queryForList("select * from alarm_rules where project_id=? order by updated_at desc", p)
        .stream()
        .map(AlertService::view)
        .toList();
  }

  @Transactional
  public Object saveRule(String id, Map<String, Object> b) {
    String p = text(b, "projectId", "");
    access.require(p, "alarm.write");
    String name = text(b, "name", "").trim(),
        source = text(b, "source", "HARDWARE"),
        severity = text(b, "severity", "WARNING"),
        metric = text(b, "metric", "CPU"),
        operator = text(b, "operator", "GT");
    double threshold = num(b, "threshold", 80);
    int duration = (int) num(b, "durationSeconds", 0);
    if (name.isBlank()
        || !Set.of("HARDWARE", "DEPLOYMENT", "DISTRIBUTION").contains(source)
        || !Set.of("CRITICAL", "WARNING", "INFO").contains(severity)
        || !Set.of("CPU", "MEMORY", "DISK", "ONLINE", "FAILURE_COUNT").contains(metric)
        || !Set.of("GT", "GTE", "LT", "LTE", "EQ").contains(operator)
        || duration < 0
        || !Double.isFinite(threshold)
        || threshold < 0
        || (!metric.equals("FAILURE_COUNT") && threshold > 100))
      throw new ApiException(400, "RULE_INVALID", "规则名称、阈值或条件不正确");
    var targets = b.getOrDefault("targetIds", List.of());
    var channels = b.getOrDefault("notificationChannels", List.of("IN_APP"));
    if (!(targets instanceof List<?> ts)
        || !(channels instanceof List<?> cs)
        || cs.isEmpty()
        || cs.stream().anyMatch(c -> !Set.of("IN_APP", "EMAIL", "WEBHOOK").contains(c)))
      throw new ApiException(400, "RULE_INVALID", "目标和通知渠道必须为有效数组");
    if (source.equals("HARDWARE") && !ts.isEmpty())
      assets.requireTargets(p, ts.stream().map(String::valueOf).toList(), false);
    if (id == null) {
      id = UUID.randomUUID().toString();
      jdbc.update(
          "insert into"
              + " alarm_rules(id,project_id,name,source,severity,metric,operator,threshold,duration_seconds,enabled,target_ids,notification_channels)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          p,
          name,
          source,
          severity,
          metric,
          operator,
          threshold,
          duration,
          Boolean.TRUE.equals(b.get("enabled")),
          Jsons.write(targets),
          Jsons.write(channels));
    } else {
      int n =
          jdbc.update(
              "update alarm_rules set"
                  + " name=?,source=?,severity=?,metric=?,operator=?,threshold=?,duration_seconds=?,enabled=?,target_ids=?,notification_channels=?,version=version+1,updated_at=current_timestamp"
                  + " where id=? and project_id=? and version=?",
              name,
              source,
              severity,
              metric,
              operator,
              threshold,
              duration,
              Boolean.TRUE.equals(b.get("enabled")),
              Jsons.write(targets),
              Jsons.write(channels),
              id,
              p,
              (long) num(b, "version", -1));
      if (n == 0) throw new ApiException(409, "VERSION_CONFLICT", "规则已变化或不存在");
      jdbc.update("delete from alarm_rule_states where rule_id=?", id);
    }
    audit.record(p, "alarm.rule.save", id, Map.of("name", name));
    return view(jdbc.queryForMap("select * from alarm_rules where id=?", id));
  }

  @Transactional
  public Object deleteRule(String id, String p, long version) {
    access.require(p, "alarm.write");
    if (jdbc.update(
            "delete from alarm_rules where id=? and project_id=? and version=?", id, p, version)
        != 1) throw new ApiException(409, "VERSION_CONFLICT", "规则已变化");
    jdbc.update("delete from alarm_rule_states where rule_id=?", id);
    audit.record(p, "alarm.rule.delete", id, Map.of("preservedHistory", true));
    return Map.of("deleted", true);
  }

  public Object alarms(String p, String status) {
    access.require(p, "alarm.read");
    return jdbc
        .queryForList(
            "select * from alarms where project_id=? and (?='' or status=?) order by created_at"
                + " desc",
            p,
            status,
            status)
        .stream()
        .map(AlertService::view)
        .toList();
  }

  public Object detail(String id, String p) {
    access.require(p, "alarm.read");
    var rows = jdbc.queryForList("select * from alarms where id=? and project_id=?", id, p);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "告警不存在");
    var m = view(rows.getFirst());
    m.put(
        "actions",
        jdbc
            .queryForList("select * from alarm_actions where alarm_id=? order by created_at", id)
            .stream()
            .map(AlertService::view)
            .toList());
    return m;
  }

  @Transactional
  public Object handle(String id, Map<String, Object> b) {
    String p = text(b, "projectId", "");
    access.require(p, "alarm.write");
    String status = text(b, "status", "ACKNOWLEDGED"), note = text(b, "note", "");
    if (!Set.of("ACKNOWLEDGED", "RESOLVED").contains(status) || note.isBlank())
      throw new ApiException(400, "HANDLING_INVALID", "请填写处理说明并选择处理状态");
    if (jdbc.update(
            "update alarms set status=?,version=version+1,updated_at=current_timestamp where id=?"
                + " and project_id=? and version=?",
            status,
            id,
            p,
            (long) num(b, "version", -1))
        != 1) throw new ApiException(409, "VERSION_CONFLICT", "告警已变化");
    jdbc.update(
        "insert into alarm_actions(id,alarm_id,actor,status,note) values (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        access.actor().subject(),
        status,
        note);
    audit.record(p, "alarm.handle", id, Map.of("status", status, "note", note));
    return detail(id, p);
  }

  @Transactional
  public Object observation(Map<String, Object> b) {
    String p = text(b, "projectId", "");
    access.require(p, "observation.write");
    String assetId = text(b, "assetId", ""),
        mode = text(b, "mode", "REAL"),
        event = text(b, "externalEventId", "");
    var target = assets.requireTargets(p, List.of(assetId), false).getFirst();
    if (!target.mode().equals(mode)
        || event.isBlank()
        || ("SIMULATED".equals(mode) && !p.equals("demo")))
      throw new ApiException(400, "MODE_INVALID", "观测来源、事件编号或执行模式不正确");
    Instant time;
    try {
      time = Instant.parse(text(b, "observedAt", ""));
    } catch (Exception ex) {
      throw new ApiException(400, "TIME_INVALID", "请提供ISO观测时间");
    }
    if (time.isAfter(Instant.now().plusSeconds(30)))
      throw new ApiException(400, "TIME_INVALID", "观测时间超前");
    if (!(b.get("metrics") instanceof Map<?, ?> raw))
      throw new ApiException(400, "METRICS_REQUIRED", "缺少观测指标");
    var metrics = new LinkedHashMap<String, Object>();
    raw.forEach((k, v) -> metrics.put(String.valueOf(k).toUpperCase(), v));
    int inserted =
        jdbc.update(
            "insert into"
                + " observations(id,project_id,asset_id,mode,metrics,external_event_id,observed_at)"
                + " values (?,?,?,?,?,?,?) on conflict(project_id,external_event_id) do nothing",
            UUID.randomUUID().toString(),
            p,
            assetId,
            mode,
            Jsons.write(metrics),
            event,
            java.sql.Timestamp.from(time));
    if (inserted == 0) return Map.of("status", "DUPLICATE");
    jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", p + ":" + assetId);
    if (time.isBefore(Instant.now().minusSeconds(300)))
      return Map.of("status", "STALE", "message", "已留存过期观测，不参与健康判断");
    var newer =
        jdbc.queryForObject(
            "select count(*) from observations where project_id=? and asset_id=? and observed_at>?",
            Integer.class,
            p,
            assetId,
            java.sql.Timestamp.from(time));
    if (newer > 0) return Map.of("status", "OUT_OF_ORDER");
    int count = 0;
    for (var rule :
        jdbc.queryForList(
            "select * from alarm_rules where project_id=? and source='HARDWARE' and enabled=true",
            p)) {
      var targets =
          Arrays.asList(Jsons.MAPPER.readValue((String) rule.get("target_ids"), String[].class));
      if (!targets.isEmpty() && !targets.contains(assetId)) continue;
      String metric = rule.get("metric").toString();
      if (!(metrics.get(metric) instanceof Number n)) continue;
      String ruleId = rule.get("id").toString();
      if (compare(
          n.doubleValue(),
          rule.get("operator").toString(),
          ((Number) rule.get("threshold")).doubleValue())) {
        var state =
            jdbc.queryForList(
                "select * from alarm_rule_states where rule_id=? and asset_id=? for update",
                ruleId,
                assetId);
        Instant since =
            state.isEmpty() || state.getFirst().get("breach_since") == null
                ? time
                : ((java.sql.Timestamp) state.getFirst().get("breach_since")).toInstant();
        if (!state.isEmpty()
            && ((java.sql.Timestamp) state.getFirst().get("observed_at"))
                .toInstant()
                .isBefore(time.minusSeconds(300))) since = time;
        jdbc.update(
            "insert into alarm_rule_states(rule_id,asset_id,breach_since,observed_at) values"
                + " (?,?,?,?) on conflict(rule_id,asset_id) do update set"
                + " breach_since=excluded.breach_since,observed_at=excluded.observed_at",
            ruleId,
            assetId,
            java.sql.Timestamp.from(since),
            java.sql.Timestamp.from(time));
        if (Duration.between(since, time).getSeconds()
            >= ((Number) rule.get("duration_seconds")).intValue()) {
          emit(
              rule,
              assetId,
              mode,
              rule.get("name") + "：" + metric + "=" + n,
              ruleId + ":" + assetId);
          count++;
        }
      } else {
        jdbc.update(
            "delete from alarm_rule_states where rule_id=? and asset_id=?", ruleId, assetId);
        jdbc.update(
            "update alarms set"
                + " observed_recovery=true,version=version+1,updated_at=current_timestamp where"
                + " dedupe_key=? and observed_recovery=false",
            ruleId + ":" + assetId);
      }
    }
    return Map.of("status", "ACCEPTED", "evaluatedAlarms", count, "mode", mode);
  }

  public static boolean compare(double v, String op, double threshold) {
    return switch (op) {
      case "GT" -> v > threshold;
      case "GTE" -> v >= threshold;
      case "LT" -> v < threshold;
      case "LTE" -> v <= threshold;
      case "EQ" -> v == threshold;
      default -> false;
    };
  }

  public String emit(
      Map<String, Object> rule, String target, String mode, String content, String key) {
    String p = rule.get("project_id").toString(), id = UUID.randomUUID().toString();
    int n =
        jdbc.update(
            "insert into"
                + " alarms(id,project_id,rule_id,source,target_id,mode,severity,content,dedupe_key)"
                + " values (?,?,?,?,?,?,?,?,?) on conflict(dedupe_key) where"
                + " observed_recovery=false do nothing",
            id,
            p,
            rule.get("id"),
            rule.get("source"),
            target,
            mode,
            rule.get("severity"),
            content,
            key);
    if (n == 0) return null;
    List<String> channels =
        Arrays.asList(
            Jsons.MAPPER.readValue(rule.get("notification_channels").toString(), String[].class));
    for (var grant :
        jdbc.queryForList(
            "select principal_id,permissions from access_grants where project_id=? and enabled=true"
                + " and service=false",
            p)) {
      List<String> ps =
          Arrays.asList(
              Jsons.MAPPER.readValue(grant.get("permissions").toString(), String[].class));
      if (!ps.contains("*") && !ps.contains("notification.read")) continue;
      for (String channel : channels) {
        boolean local = channel.equals("IN_APP");
        jdbc.update(
            "insert into"
                + " notifications(id,project_id,alarm_id,recipient_id,channel,status,message,attempts,last_error)"
                + " values (?,?,?,?,?,?,?,?,?) on conflict do nothing",
            UUID.randomUUID().toString(),
            p,
            id,
            grant.get("principal_id"),
            channel,
            local ? "AVAILABLE" : "NOT_CONFIGURED",
            content,
            local ? 1 : 0,
            local ? null : "真实通知适配未配置，未发送");
      }
    }
    audit.record(p, "alarm.created", id, Map.of("mode", mode, "targetId", target));
    return id;
  }

  public Object notifications(String p) {
    access.require(p, "notification.read");
    return jdbc
        .queryForList(
            "select * from notifications where project_id=? and recipient_id=? order by created_at"
                + " desc",
            p,
            access.actor().subject())
        .stream()
        .map(AlertService::view)
        .toList();
  }

  public Object read(String id, String p) {
    access.require(p, "notification.read");
    if (jdbc.update(
            "update notifications set is_read=true where id=? and project_id=? and recipient_id=?",
            id,
            p,
            access.actor().subject())
        != 1) throw new ApiException(404, "NOT_FOUND", "通知不存在");
    return Map.of("read", true);
  }

  @Transactional
  public Object test(String id, String p) {
    access.require(p, "alarm.write");
    var rows = jdbc.queryForList("select * from alarm_rules where id=? and project_id=?", id, p);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "规则不存在");
    var rule = rows.getFirst();
    var channels =
        Arrays.asList(
            Jsons.MAPPER.readValue(rule.get("notification_channels").toString(), String[].class));
    var receipts = new ArrayList<Map<String, Object>>();
    for (String channel : channels) {
      String notificationId = UUID.randomUUID().toString();
      boolean local = channel.equals("IN_APP");
      jdbc.update(
          "insert into"
              + " notifications(id,project_id,recipient_id,channel,status,message,attempts,last_error)"
              + " values (?,?,?,?,?,?,?,?)",
          notificationId,
          p,
          access.actor().subject(),
          channel,
          local ? "AVAILABLE" : "NOT_CONFIGURED",
          "通知测试：" + rule.get("name"),
          local ? 1 : 0,
          local ? null : "真实通知适配未配置，未发送");
      receipts.add(
          Map.of(
              "channel",
              channel,
              "notificationId",
              notificationId,
              "status",
              local ? "AVAILABLE" : "NOT_CONFIGURED",
              "message",
              local ? "站内测试通知已保存" : "真实通知适配未配置，未发送"));
    }
    audit.record(p, "notification.test", id, Map.of("channels", channels));
    return Map.of("receipts", receipts);
  }
}
