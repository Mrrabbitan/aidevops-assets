package cn.aidevops.assets.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.aidevops.assets.common.Jsons;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AssetMonitoringTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final Instant now = Instant.parse("2026-10-22T01:00:00Z");
  final AssetMonitoring monitoring = new AssetMonitoring(jdbc, Clock.fixed(now, ZoneOffset.UTC));

  @Test
  void missingOrStaleDataDoesNotShowHealthy() {
    when(jdbc.queryForList(anyString(), eq("demo"), eq("a"), eq("REAL"))).thenReturn(List.of());
    assertEquals("UNKNOWN", monitoring.latest("demo", "a", "REAL").get("status"));
    when(jdbc.queryForList(anyString(), eq("demo"), eq("a"), eq("REAL")))
        .thenReturn(List.of(row(now.minusSeconds(301))));
    var stale = monitoring.latest("demo", "a", "REAL");
    assertEquals("UNKNOWN", stale.get("status"));
    assertEquals("STALE_OBSERVATION", stale.get("reason"));
    assertEquals(95, AssetService.asMap(stale.get("metrics")).get("CPU"));
    verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(), any(), any());
  }

  @Test
  void freshSimulatedObservationRetainsProvenanceAndActualAlarmState() {
    when(jdbc.queryForList(anyString(), eq("demo"), eq("a"), eq("SIMULATED")))
        .thenReturn(List.of(row(now.minusSeconds(2))));
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("demo"), eq("a"), eq("SIMULATED")))
        .thenReturn(1L);
    var actual = monitoring.latest("demo", "a", "SIMULATED");
    assertEquals("ALERT", actual.get("status"));
    assertEquals(true, actual.get("simulated"));
    assertEquals("SIMULATED_SOURCE", actual.get("reason"));
    assertEquals(now.minusSeconds(2), actual.get("observedAt"));
  }

  private Map<String, Object> row(Instant time) {
    return Map.of(
        "id",
        "obs-1",
        "mode",
        "SIMULATED",
        "metrics",
        Jsons.write(Map.of("CPU", 95, "ONLINE", 1)),
        "external_event_id",
        "event-1",
        "observed_at",
        java.sql.Timestamp.from(time));
  }
}
