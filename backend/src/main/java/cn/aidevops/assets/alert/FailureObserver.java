package cn.aidevops.assets.alert;

import cn.aidevops.assets.security.Access;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "assets.role", havingValue = "worker")
public class FailureObserver {
  private static final Logger log = LoggerFactory.getLogger(FailureObserver.class);
  private final JdbcTemplate jdbc;
  private final Access access;
  private final AlertService alerts;

  public FailureObserver(JdbcTemplate j, Access a, AlertService s) {
    jdbc = j;
    access = a;
    alerts = s;
  }

  @Scheduled(fixedDelayString = "${assets.alert-poll-ms:5000}")
  @Transactional
  public void evaluate() {
    for (var run :
        jdbc.queryForList(
            "select r.id,r.task_id,r.project_id,r.mode,r.status,t.kind,t.name from task_runs r join"
                + " tasks t on t.id=r.task_id where t.kind in ('DEPLOYMENT','DISTRIBUTION') and"
                + " r.status in ('FAILED','PARTIAL_FAILED') and r.finished_at>now()-interval '1"
                + " day'")) {
      String p = run.get("project_id").toString();
      try {
        access.authorizeSnapshot(access.serviceActor("asset-scheduler-demo"), p, "alarm.evaluate");
      } catch (Exception e) {
        continue;
      }
      String source = run.get("kind").equals("DISTRIBUTION") ? "DISTRIBUTION" : "DEPLOYMENT";
      for (var rule :
          jdbc.queryForList(
              "select * from alarm_rules where project_id=? and enabled=true and source=?",
              p,
              source)) {
        String[] targets =
            cn.aidevops.assets.common.Jsons.MAPPER.readValue(
                rule.get("target_ids").toString(), String[].class);
        if (targets.length > 0
            && !java.util.Arrays.asList(targets).contains(run.get("task_id").toString())) continue;
        long failures =
            jdbc.queryForObject(
                "select count(*) from task_runs where task_id=? and status in"
                    + " ('FAILED','PARTIAL_FAILED') and finished_at>now()-interval '1 hour'",
                Long.class,
                run.get("task_id"));
        if (AlertService.compare(
            failures,
            rule.get("operator").toString(),
            ((Number) rule.get("threshold")).doubleValue()))
          try {
            alerts.emit(
                rule,
                run.get("task_id").toString(),
                run.get("mode").toString(),
                run.get("name") + " 执行失败（" + run.get("id") + "）",
                rule.get("id") + ":" + run.get("id"));
          } catch (Exception e) {
            log.warn("failure observation failed: {}", e.getClass().getSimpleName());
          }
      }
    }
  }
}
