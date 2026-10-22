package cn.aidevops.assets.asset;

import cn.aidevops.assets.common.Jsons;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AssetMonitoring {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  @Autowired
  public AssetMonitoring(JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  AssetMonitoring(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Map<String, Object> latest(String projectId, String assetId, String mode) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select id,mode,metrics,external_event_id,observed_at from observations where"
                + " project_id=? and asset_id=? and mode=? order by observed_at desc,received_at"
                + " desc limit 1",
            projectId,
            assetId,
            mode);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("status", "UNKNOWN");
    r.put("reason", "NO_OBSERVATION");
    r.put("mode", mode);
    r.put("simulated", "SIMULATED".equals(mode));
    r.put("source", "SIMULATED".equals(mode) ? "SIMULATED_OBSERVATION" : "OBSERVATION");
    r.put("observedAt", null);
    r.put("metrics", Map.of());
    r.put("freshnessSeconds", 300);
    if (rows.isEmpty()) return r;
    Map<String, Object> row = rows.getFirst();
    Instant time = instant(row.get("observed_at"));
    r.put("observedAt", time);
    r.put("observationId", row.get("id"));
    r.put("externalEventId", row.get("external_event_id"));
    Map<String, Object> metrics = Jsons.readMap(Objects.toString(row.get("metrics"), "{}"));
    r.put("metrics", metrics);
    if (time == null) {
      r.put("reason", "INVALID_OBSERVATION_TIME");
      return r;
    }
    if (time.isAfter(clock.instant().plusSeconds(30))) {
      r.put("reason", "FUTURE_OBSERVATION");
      return r;
    }
    if (time.isBefore(clock.instant().minusSeconds(300))) {
      r.put("reason", "STALE_OBSERVATION");
      return r;
    }
    if (metrics.isEmpty()) {
      r.put("reason", "EMPTY_OBSERVATION");
      return r;
    }
    Long count =
        jdbc.queryForObject(
            "select count(*) from alarms where project_id=? and target_id=? and mode=? and"
                + " source='HARDWARE' and observed_recovery=false",
            Long.class,
            projectId,
            assetId,
            mode);
    String status = count != null && count > 0 ? "ALERT" : "OBSERVED";
    if (metrics.get("ONLINE") instanceof Number online && online.doubleValue() == 0)
      status = "OFFLINE";
    r.put("status", status);
    r.put("reason", "SIMULATED".equals(mode) ? "SIMULATED_SOURCE" : "FRESH_OBSERVATION");
    r.put("activeAlarms", count == null ? 0 : count);
    return r;
  }

  static Instant instant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    if (value instanceof OffsetDateTime t) return t.toInstant();
    if (value instanceof ZonedDateTime t) return t.toInstant();
    if (value instanceof String s) {
      try {
        return Instant.parse(s);
      } catch (Exception ignored) {
        return null;
      }
    }
    return null;
  }
}
