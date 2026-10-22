package cn.aidevops.assets.task;

import static org.junit.jupiter.api.Assertions.*;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.task.TaskModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskRulesTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "CONTAINER_PLATFORM",
        "MICROSERVICE_FRAMEWORK",
        "CLIENT_ENVIRONMENT",
        "HOTUPDATE_SERVER"
      })
  void fourEnvironmentsRequireSpecificConfiguration(String type) {
    var errors = TaskRules.errors("ENVIRONMENT", type, Map.of("targetIds", List.of("host")));
    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(e -> e.contains("version")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FLATPAK", "CLIENT_PUSH", "FLUTTER_HOTUPDATE"})
  void threeDistributionTypesRequireTheirOwnPayload(String type) {
    assertFalse(
        TaskRules.errors("DISTRIBUTION", type, Map.of("targetIds", List.of("client"))).isEmpty());
  }

  @Test
  void deploymentRequiresImmutableArtifactDigestAndBoundedTimeout() {
    var c = new HashMap<String, Object>();
    c.putAll(
        Map.of(
            "targetIds",
            List.of("host"),
            "artifact",
            "registry/release:1",
            "environment",
            "environment-task",
            "namespace",
            "app",
            "strategy",
            "CANARY",
            "healthCheck",
            "/health",
            "timeoutSeconds",
            30,
            "failurePolicy",
            "STOP",
            "artifactDigest",
            "sha256:" + "a".repeat(64)));
    c.put("canaryRatio", 10);
    assertEquals(List.of(), TaskRules.errors("DEPLOYMENT", "APPLICATION", c));
    c.put("artifactDigest", "latest");
    c.put("canaryRatio", 100);
    assertEquals(2, TaskRules.errors("DEPLOYMENT", "APPLICATION", c).size());
  }

  @Test
  void nestedSecretsAreRejectedButReferencesAreAccepted() {
    assertThrows(
        ApiException.class,
        () -> TaskRules.rejectSecrets(Map.of("nodes", List.of(Map.of("password", "hidden")))));
    assertDoesNotThrow(
        () -> TaskRules.rejectSecrets(Map.of("credentialRef", "env:PVE_CREDENTIAL")));
  }

  @Test
  void nullEnumsReturnValidationInsteadOfNullPointer() {
    var error =
        assertThrows(
            ApiException.class,
            () ->
                TaskRules.shape(new SaveRequest("demo", "task", null, null, null, null, Map.of())));
    assertEquals(400, error.status);
  }

  @Test
  void targetDuplicatesAndClusterMembershipAreValidated() {
    var errors =
        TaskRules.errors(
            "CLUSTER",
            "PVE",
            Map.of("targetIds", List.of("one", "one"), "physicalAssetIds", List.of("two")));
    assertTrue(errors.stream().anyMatch(e -> e.contains("重复")));
    assertTrue(errors.stream().anyMatch(e -> e.contains("物理节点")));
  }

  @Test
  void singleVmCannotBeExecutedAcrossMultipleHosts() {
    var errors =
        TaskRules.errors(
            "VM", "GENERAL", Map.of("targetIds", List.of("host1", "host2"), "hostId", "host1"));
    assertTrue(errors.stream().anyMatch(e -> e.contains("只包含")));
  }

  @Test
  void scheduleDefaultsToDisabledAndRejectsUnboundedIntervals() {
    var c = new HashMap<String, Object>();
    c.put("schedule", Map.of("enabled", true, "intervalMinutes", 0));
    var errors = TaskRules.errors("ENVIRONMENT", "CLIENT_ENVIRONMENT", c);
    assertTrue(errors.stream().anyMatch(e -> e.contains("intervalMinutes")));
    assertTrue(errors.stream().anyMatch(e -> e.contains("serviceSubject")));
  }

  @Test
  void versionMismatchDoesNotAcceptStaleConfirmation() {
    var task =
        new Task(
            "id",
            "demo",
            "task",
            "CLUSTER",
            "PVE",
            "SIMULATED",
            2,
            "DRAFT",
            Map.of(),
            null,
            null,
            null);
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(ApiException.class, () -> TaskService.version(task, 1L)).code);
  }

  @Test
  void simulationCannotOperateRealProjects() {
    var adapter = new SimulationTaskAdapter();
    var run =
        new Run(
            "r",
            "t",
            "production",
            1,
            "SIMULATED",
            "RUNNING",
            0,
            Map.of(),
            List.of(),
            null,
            null,
            null);
    assertEquals(
        "SIMULATION_ISOLATION",
        assertThrows(
                ApiException.class,
                () -> adapter.execute(run, new RunTarget("x", "h", "host", "PENDING", Map.of())))
            .code);
  }

  @Test
  void realAdapterAlwaysReportsNotConfigured() {
    var adapter = new UnconfiguredTaskAdapter();
    assertEquals(
        "NOT_CONFIGURED", assertThrows(ApiException.class, () -> adapter.execute(null, null)).code);
  }
}
