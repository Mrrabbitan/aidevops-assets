package cn.aidevops.assets.common;

import java.util.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class Audit {
  private final JdbcTemplate jdbc;

  public Audit(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(String projectId, String action, String objectId, Object details) {
    var a = SecurityContextHolder.getContext().getAuthentication();
    var internal = cn.aidevops.assets.security.Access.internalActor();
    String subject =
        internal != null
            ? internal.subject()
            : a != null && a.getPrincipal() instanceof Jwt j ? j.getSubject() : "worker";
    jdbc.update(
        "insert into"
            + " audit_events(id,project_id,action,object_id,actor,details,trace_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,current_timestamp)",
        UUID.randomUUID().toString(),
        projectId,
        action,
        objectId,
        subject,
        Jsons.write(redact(details)),
        Objects.toString(MDC.get("traceId"), UUID.randomUUID().toString()));
  }

  private Object redact(Object v) {
    if (v instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, Object>();
      m.forEach(
          (k, x) -> {
            String key = String.valueOf(k);
            out.put(
                key,
                key.toLowerCase()
                        .matches(".*(password|secret|token|authorization|kubeconfig).* ".trim())
                    ? "[REDACTED]"
                    : redact(x));
          });
      return out;
    }
    if (v instanceof Collection<?> c) return c.stream().map(this::redact).toList();
    return v;
  }
}
