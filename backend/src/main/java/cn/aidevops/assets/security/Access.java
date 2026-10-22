package cn.aidevops.assets.security;

import cn.aidevops.assets.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class Access {
  public record Actor(
      String subject,
      Set<String> roles,
      Set<String> projects,
      Set<String> permissions,
      boolean service) {}

  private static final ThreadLocal<Actor> INTERNAL = new ThreadLocal<>();

  public static Actor internalActor() {
    return INTERNAL.get();
  }

  public <T> T runAs(Actor actor, java.util.function.Supplier<T> work) {
    Actor previous = INTERNAL.get();
    INTERNAL.set(actor);
    try {
      return work.get();
    } finally {
      if (previous == null) INTERNAL.remove();
      else INTERNAL.set(previous);
    }
  }

  private final JdbcTemplate jdbc;

  public Access(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Actor actor() {
    if (INTERNAL.get() != null) return INTERNAL.get();
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt))
      throw new ApiException(401, "UNAUTHENTICATED", "请先登录");
    if (jwt.getSubject() == null || jwt.getSubject().isBlank())
      throw new ApiException(401, "SUBJECT_REQUIRED", "身份声明缺少主体，请重新登录");
    Set<String> roles = new HashSet<>();
    var realm = jwt.getClaimAsMap("realm_access");
    if (realm != null && realm.get("roles") instanceof List<?> rs)
      rs.forEach(v -> roles.add(String.valueOf(v)));
    return load(jwt.getSubject(), roles, false);
  }

  private Actor load(String subject, Set<String> roles, boolean requireService) {
    var rows =
        jdbc.queryForList(
            "select project_id,permissions,service from access_grants where principal_id=? and"
                + " enabled=true",
            subject);
    Set<String> projects = new HashSet<>(), permissions = new HashSet<>();
    boolean service = false;
    for (var row : rows) {
      if (requireService && !Boolean.TRUE.equals(row.get("service"))) continue;
      projects.add((String) row.get("project_id"));
      permissions.addAll(
          Arrays.asList(Jsons.MAPPER.readValue((String) row.get("permissions"), String[].class)));
      service |= Boolean.TRUE.equals(row.get("service"));
    }
    return new Actor(
        subject, Set.copyOf(roles), Set.copyOf(projects), Set.copyOf(permissions), service);
  }

  public Actor serviceActor(String subject) {
    var actor = load(subject, Set.of("service"), true);
    if (actor.projects().isEmpty())
      throw new ApiException(403, "SERVICE_NOT_AUTHORIZED", "服务身份未授权或已停用");
    return actor;
  }

  public void require(String projectId, String permission) {
    authorizeSnapshot(actor(), projectId, permission);
  }

  public void authorizeSnapshot(Actor actor, String projectId, String permission) {
    if (projectId == null || projectId.isBlank())
      throw new ApiException(400, "PROJECT_REQUIRED", "必须选择项目");
    if (actor == null
        || !actor.projects().contains(projectId)
        || !(actor.permissions().contains(permission) || actor.permissions().contains("*")))
      throw new ApiException(403, "FORBIDDEN", "无此项目或操作权限");
    var rows =
        jdbc.queryForList(
            "select permissions,service from access_grants where principal_id=? and project_id=?"
                + " and enabled=true",
            actor.subject(),
            projectId);
    if (rows.isEmpty()) throw new ApiException(403, "FORBIDDEN", "授权已撤销");
    var row = rows.getFirst();
    if (actor.service() != Boolean.TRUE.equals(row.get("service")))
      throw new ApiException(403, "IDENTITY_KIND_MISMATCH", "服务身份与人员身份不可互换");
    var ps = Arrays.asList(Jsons.MAPPER.readValue((String) row.get("permissions"), String[].class));
    if (!ps.contains(permission) && !ps.contains("*"))
      throw new ApiException(403, "FORBIDDEN", "无此操作权限");
  }

  public boolean can(String projectId, String permission) {
    try {
      require(projectId, permission);
      return true;
    } catch (ApiException ex) {
      return false;
    }
  }

  public Set<String> visibleProjects() {
    return actor().projects();
  }
}
