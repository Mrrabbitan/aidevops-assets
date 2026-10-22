package cn.aidevops.assets.common;

import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "assets.demo-enabled", havingValue = "true")
public class DemoInitializer implements ApplicationRunner {
  private final JdbcTemplate jdbc;

  public DemoInitializer(JdbcTemplate j) {
    jdbc = j;
  }

  public void run(ApplicationArguments args) {
    List<String> read =
        List.of(
            "dashboard.read",
            "asset.read",
            "task.read",
            "alarm.read",
            "notification.read",
            "connection.read",
            "audit.read");
    var ops = new ArrayList<>(read);
    ops.addAll(
        List.of(
            "asset.write",
            "asset.execute",
            "task.write",
            "task.execute",
            "deployment.confirm",
            "alarm.write",
            "alarm.evaluate",
            "observation.write"));
    seed("11111111-1111-4111-8111-111111111111", "demo", List.of("*"), false);
    seed("22222222-2222-4222-8222-222222222222", "demo", ops, false);
    seed("33333333-3333-4333-8333-333333333333", "demo", read, false);
    seed("44444444-4444-4444-8444-444444444444", "demo", read, false);
    seed("55555555-5555-4555-8555-555555555555", "demo", read, false);
    seed("11111111-1111-4111-8111-111111111111", "sandbox", List.of("*"), false);
    seed(
        "asset-scheduler-demo",
        "demo",
        List.of(
            "task.execute",
            "task.read",
            "asset.read",
            "asset.execute",
            "alarm.evaluate",
            "observation.write",
            "deployment.confirm"),
        true);
    for (String kind :
        List.of(
            "DISCOVERY",
            "PVE",
            "K8S",
            "PIPELINE",
            "ARTIFACT",
            "MONITORING",
            "NOTIFICATION",
            "FLUTTER",
            "FLATPAK")) {
      jdbc.update(
          "insert into connections(id,project_id,name,kind,mode,enabled) values"
              + " (?,?,?,?,'SIMULATED',true) on conflict do nothing",
          "demo-" + kind.toLowerCase(),
          "demo",
          kind + " 演示适配",
          kind);
      jdbc.update(
          "insert into connections(id,project_id,name,kind,mode,enabled) values"
              + " (?,?,?,?,'REAL',false) on conflict do nothing",
          "sandbox-" + kind.toLowerCase(),
          "sandbox",
          kind + " 待接入",
          kind);
    }
  }

  private void seed(String id, String p, List<String> permissions, boolean service) {
    jdbc.update(
        "insert into access_grants(principal_id,project_id,permissions,enabled,service) values"
            + " (?,?,?,true,?) on conflict do nothing",
        id,
        p,
        Jsons.write(permissions),
        service);
  }
}
