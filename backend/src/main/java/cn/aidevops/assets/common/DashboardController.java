package cn.aidevops.assets.common;

import cn.aidevops.assets.alert.AlertService;
import cn.aidevops.assets.security.Access;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {
  private final Access access;
  private final JdbcTemplate jdbc;

  public DashboardController(Access a, JdbcTemplate j) {
    access = a;
    jdbc = j;
  }

  @GetMapping("/dashboard")
  public Object dashboard(@RequestParam String projectId) {
    access.require(projectId, "dashboard.read");
    access.require(projectId, "asset.read");
    access.require(projectId, "task.read");
    access.require(projectId, "alarm.read");
    var counts = new LinkedHashMap<String, Object>();
    counts.put(
        "assets",
        count("select count(*) from assets where project_id=? and deleted=false", projectId));
    counts.put(
        "admitted",
        count(
            "select count(*) from assets where project_id=? and admission='ADMITTED' and"
                + " deleted=false",
            projectId));
    counts.put(
        "pending",
        count(
            "select count(*) from assets where project_id=? and admission in ('PENDING','DRAFT')"
                + " and deleted=false",
            projectId));
    Map.of(
            "clusters",
            "CLUSTER",
            "vms",
            "VM",
            "environments",
            "ENVIRONMENT",
            "deployments",
            "DEPLOYMENT",
            "distributions",
            "DISTRIBUTION")
        .forEach(
            (key, kind) ->
                counts.put(
                    key,
                    jdbc.queryForObject(
                        "select count(*) from tasks where project_id=? and kind=?",
                        Long.class,
                        projectId,
                        kind)));
    counts.put(
        "openAlarms",
        count("select count(*) from alarms where project_id=? and status<>'RESOLVED'", projectId));
    counts.put(
        "runningTasks",
        count(
            "select count(*) from task_runs where project_id=? and status in ('QUEUED','RUNNING')",
            projectId));
    return Map.of(
        "projectId",
        projectId,
        "mode",
        "demo".equals(projectId) ? "SIMULATED" : "REAL",
        "counts",
        counts,
        "assetTypes",
        jdbc.queryForList(
            "select type,count(*) as count from assets where project_id=? and deleted=false group"
                + " by type order by type",
            projectId),
        "recentAssets",
        jdbc
            .queryForList(
                "select id,name,type,mode,admission,updated_at from assets where project_id=? and"
                    + " deleted=false order by updated_at desc limit 5",
                projectId)
            .stream()
            .map(AlertService::view)
            .toList(),
        "recentRuns",
        jdbc
            .queryForList(
                "select r.id,t.name,r.mode,r.status,r.created_at from task_runs r join tasks t on"
                    + " t.id=r.task_id where r.project_id=? order by r.created_at desc limit 5",
                projectId)
            .stream()
            .map(AlertService::view)
            .toList(),
        "alarms",
        jdbc
            .queryForList(
                "select * from alarms where project_id=? order by created_at desc limit 5",
                projectId)
            .stream()
            .map(AlertService::view)
            .toList());
  }

  private long count(String sql, String p) {
    return jdbc.queryForObject(sql, Long.class, p);
  }
}
