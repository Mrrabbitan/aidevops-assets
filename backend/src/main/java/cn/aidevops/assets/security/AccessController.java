package cn.aidevops.assets.security;

import cn.aidevops.assets.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccessController {
  private final Access access;
  private final JdbcTemplate jdbc;
  private final Audit audit;

  public AccessController(Access access, JdbcTemplate jdbc, Audit audit) {
    this.access = access;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @GetMapping("/auth/me")
  public Object me() {
    var actor = access.actor();
    var projectPermissions = new LinkedHashMap<String, Object>();
    jdbc.queryForList(
            "select project_id,permissions from access_grants where principal_id=? and"
                + " enabled=true",
            actor.subject())
        .forEach(
            row ->
                projectPermissions.put(
                    row.get("project_id").toString(),
                    Jsons.MAPPER.readValue(row.get("permissions").toString(), String[].class)));
    return Map.of(
        "projectPermissions",
        projectPermissions,
        "subject",
        actor.subject(),
        "name",
        actor.subject(),
        "roles",
        actor.roles(),
        "projects",
        actor.projects(),
        "permissions",
        actor.permissions(),
        "service",
        actor.service());
  }

  @GetMapping("/access-grants")
  public Object list(@RequestParam String projectId) {
    access.require(projectId, "access.manage");
    return jdbc.queryForList(
        "select principal_id,project_id,permissions,enabled,service from access_grants where"
            + " project_id=? order by principal_id",
        projectId);
  }

  @PutMapping("/access-grants/{principalId}")
  public Object update(@PathVariable String principalId, @RequestBody Map<String, Object> b) {
    String p = Objects.toString(b.get("projectId"), "");
    access.require(p, "access.manage");
    Object permissions = b.get("permissions");
    if (!(permissions instanceof List<?>))
      throw new ApiException(400, "VALIDATION_ERROR", "permissions必须为数组");
    jdbc.update(
        "insert into access_grants(principal_id,project_id,permissions,enabled,service) values"
            + " (?,?,?,?,?) on conflict(principal_id,project_id) do update set"
            + " permissions=excluded.permissions,enabled=excluded.enabled,service=excluded.service",
        principalId,
        p,
        Jsons.write(permissions),
        Boolean.TRUE.equals(b.get("enabled")),
        Boolean.TRUE.equals(b.get("service")));
    audit.record(
        p, "access.update", principalId, Map.of("enabled", Boolean.TRUE.equals(b.get("enabled"))));
    return Map.of("updated", true);
  }

  @GetMapping("/audit")
  public Object audit(
      @RequestParam String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    access.require(projectId, "audit.read");
    int size = Math.clamp(pageSize, 1, 100);
    return new PageResult<>(
        jdbc.queryForList(
            "select * from audit_events where project_id=? order by occurred_at desc,id limit ?"
                + " offset ?",
            projectId,
            size,
            Math.max(0, page - 1) * size),
        jdbc.queryForObject(
            "select count(*) from audit_events where project_id=?", Long.class, projectId),
        Math.max(1, page),
        size);
  }
}
