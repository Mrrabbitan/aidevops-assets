package cn.aidevops.assets.integration;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {
  private final JdbcTemplate jdbc;
  private final Access access;
  private final Audit audit;

  public ConnectionController(JdbcTemplate j, Access a, Audit u) {
    jdbc = j;
    access = a;
    audit = u;
  }

  @GetMapping
  public Object list(@RequestParam String projectId) {
    access.require(projectId, "connection.read");
    return jdbc
        .queryForList("select * from connections where project_id=? order by kind,id", projectId)
        .stream()
        .map(this::view)
        .toList();
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var m = new LinkedHashMap<>(row);
    m.put("projectId", row.get("project_id"));
    m.put("credentialRef", row.get("credential_ref"));
    m.put(
        "status",
        "SIMULATED".equals(row.get("mode")) && Boolean.TRUE.equals(row.get("enabled"))
            ? "SIMULATED"
            : "NOT_CONFIGURED");
    return m;
  }

  @PutMapping("/{id}")
  public Object save(@PathVariable String id, @RequestBody Map<String, Object> b) {
    String p = Objects.toString(b.get("projectId"), "");
    access.require(p, "connection.write");
    String mode = Objects.toString(b.get("mode"), "REAL");
    if (!Set.of("REAL", "SIMULATED").contains(mode)
        || ("SIMULATED".equals(mode) && !"demo".equals(p)))
      throw new ApiException(400, "MODE_INVALID", "模拟连接仅限演示项目");
    String endpoint = Objects.toString(b.get("endpoint"), "");
    if (!endpoint.isBlank()) {
      var u = URI.create(endpoint);
      if (!Set.of("https", "http").contains(u.getScheme())
          || u.getHost() == null
          || u.getUserInfo() != null)
        throw new ApiException(400, "ENDPOINT_INVALID", "端点需为不含凭据的HTTP地址");
    }
    String ref = Objects.toString(b.get("credentialRef"), "");
    if (!ref.isBlank() && !ref.matches("[A-Za-z0-9_./:-]{1,160}"))
      throw new ApiException(400, "CREDENTIAL_REFERENCE_INVALID", "仅允许凭据引用");
    var old = jdbc.queryForList("select * from connections where id=? and project_id=?", id, p);
    if (old.isEmpty()) throw new ApiException(404, "NOT_FOUND", "连接不存在");
    long version = ((Number) b.getOrDefault("version", -1)).longValue();
    int n =
        jdbc.update(
            "update connections set"
                + " name=?,mode=?,enabled=?,endpoint=?,credential_ref=?,version=version+1,updated_at=current_timestamp"
                + " where id=? and project_id=? and version=?",
            Objects.toString(b.get("name"), old.getFirst().get("name").toString()),
            mode,
            Boolean.TRUE.equals(b.get("enabled")),
            endpoint,
            ref,
            id,
            p,
            version);
    if (n == 0) throw new ApiException(409, "VERSION_CONFLICT", "连接配置已变化");
    audit.record(p, "connection.save", id, Map.of("mode", mode, "credentialRef", ref));
    return view(jdbc.queryForMap("select * from connections where id=?", id));
  }

  @PostMapping("/{id}/test")
  public Object test(@PathVariable String id, @RequestBody Map<String, Object> b) {
    String p = Objects.toString(b.get("projectId"), "");
    access.require(p, "connection.write");
    var rows = jdbc.queryForList("select * from connections where id=? and project_id=?", id, p);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "连接不存在");
    return Map.of(
        "status",
        "SIMULATED".equals(rows.getFirst().get("mode")) ? "SIMULATED" : "NOT_CONFIGURED",
        "message",
        "本期提供适配契约；真实联通需现场适配实现与验证");
  }
}
