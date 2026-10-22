package cn.aidevops.assets.integration;

import java.time.Instant;
import java.util.Map;

public final class CollectionScheduleModels {
  private CollectionScheduleModels() {}

  public record Plan(
      String id,
      String projectId,
      String name,
      String kind,
      String mode,
      long version,
      boolean enabled,
      int intervalMinutes,
      String timezone,
      String serviceSubject,
      Map<String, Object> config,
      Instant nextRunAt,
      Instant createdAt,
      Instant updatedAt) {}

  public record Save(
      String projectId,
      String name,
      String kind,
      String mode,
      Long version,
      Boolean enabled,
      Integer intervalMinutes,
      String timezone,
      String serviceSubject,
      Map<String, Object> config) {}

  public record Version(Long version) {}

  public record Run(
      String id,
      String scheduleId,
      String projectId,
      String kind,
      String mode,
      long scheduleVersion,
      String status,
      String actor,
      String requestedBy,
      Map<String, Object> result,
      String previewId,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt) {}

  public record Claim(
      String id, String workerId, long fencingToken, Map<String, Object> snapshot) {}

  public record Resolve(String evidenceRef, String comment) {}
}
