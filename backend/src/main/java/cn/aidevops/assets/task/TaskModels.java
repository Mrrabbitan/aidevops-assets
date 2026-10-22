package cn.aidevops.assets.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TaskModels {
  private TaskModels() {}

  public record Task(
      String id,
      String projectId,
      String name,
      String kind,
      String type,
      String mode,
      long version,
      String status,
      Map<String, Object> config,
      Long confirmedVersion,
      Instant createdAt,
      Instant updatedAt) {}

  public record SaveRequest(
      String projectId,
      String name,
      String kind,
      String type,
      String mode,
      Long version,
      Map<String, Object> config) {}

  public record VersionRequest(Long version) {}

  public record ExecuteRequest(Long version, String idempotencyKey) {}

  public record ReconcileRequest(String outcome, String evidenceRef, String comment) {}

  public record Validation(
      boolean valid, List<String> errors, long version, String executionMode) {}

  public record Run(
      String id,
      String taskId,
      String projectId,
      long taskVersion,
      String mode,
      String status,
      int progress,
      Map<String, Object> snapshot,
      List<RunTarget> targets,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt) {}

  public record RunTarget(
      String id, String targetId, String name, String status, Map<String, Object> result) {}

  public record Log(
      long sequence, String targetId, String level, String message, Instant createdAt) {}

  public record Lease(
      String runId, String workerId, long fencingToken, Map<String, Object> actor) {}
}
