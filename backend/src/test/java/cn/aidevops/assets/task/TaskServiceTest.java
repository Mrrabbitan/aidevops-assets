package cn.aidevops.assets.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import cn.aidevops.assets.task.TaskModels.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskServiceTest {
  TaskRepository repo = mock(TaskRepository.class);
  Access access = mock(Access.class);
  AssetsPort assets = mock(AssetsPort.class);
  Audit audit = mock(Audit.class);
  TaskService service = new TaskService(repo, access, assets, audit);
  Access.Actor actor =
      new Access.Actor(
          "ops",
          Set.of("ops"),
          Set.of("demo"),
          Set.of("task.execute", "task.write", "deployment.confirm"),
          false);

  @BeforeEach
  void setup() {
    when(repo.transaction(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
    when(access.actor()).thenReturn(actor);
  }

  Task cluster(String mode, Map<String, Object> additional) {
    var config =
        new LinkedHashMap<String, Object>(
            Map.of(
                "targetIds",
                List.of("host"),
                "physicalAssetIds",
                List.of("host"),
                "platformVersion",
                "8",
                "roles",
                Map.of("host", "master"),
                "network",
                "192.0.2.0/24",
                "storage",
                "local",
                "credentialRef",
                "env:PVE"));
    config.putAll(additional);
    return new Task(
        "task", "demo", "cluster", "CLUSTER", "PVE", mode, 1, "DRAFT", config, null, null, null);
  }

  @Test
  void realExecutionFailsBeforeItCanEnqueue() {
    var t = cluster("REAL", Map.of());
    when(repo.task("task", false)).thenReturn(t);
    when(repo.task("task", true)).thenReturn(t);
    when(repo.receipt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    when(assets.requireTargets("demo", List.of("host"), true))
        .thenReturn(
            List.of(
                new AssetTarget(
                    "host", "Host", "SERVER", "REAL", "ADMITTED", "READY", 1, Map.of())));
    var e =
        assertThrows(
            ApiException.class,
            () -> service.execute("task", new ExecuteRequest(1L, "request-1234")));
    assertEquals("NOT_CONFIGURED", e.code);
    verify(repo, never()).enqueue(any(), anyMap(), any(), anyString(), anyString(), anyList());
  }

  @Test
  void simulationRefusesRealTargets() {
    var t = cluster("SIMULATED", Map.of());
    when(assets.requireTargets("demo", List.of("host"), true))
        .thenReturn(
            List.of(
                new AssetTarget(
                    "host", "Host", "SERVER", "REAL", "ADMITTED", "READY", 1, Map.of())));
    assertEquals(
        "MODE_MISMATCH", assertThrows(ApiException.class, () -> service.check(t, actor)).code);
  }

  @Test
  void productionRequiresConfirmationBeforeQueueing() {
    var t = cluster("SIMULATED", Map.of("production", true));
    when(repo.task("task", false)).thenReturn(t);
    when(repo.task("task", true)).thenReturn(t);
    when(repo.receipt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    when(assets.requireTargets("demo", List.of("host"), true))
        .thenReturn(
            List.of(
                new AssetTarget(
                    "host", "Host", "SERVER", "SIMULATED", "ADMITTED", "READY", 1, Map.of())));
    assertEquals(
        "CONFIRMATION_REQUIRED",
        assertThrows(
                ApiException.class,
                () -> service.execute("task", new ExecuteRequest(1L, "request-1234")))
            .code);
    verify(repo, never()).enqueue(any(), anyMap(), any(), anyString(), anyString(), anyList());
  }

  @Test
  void changedAssetVersionInvalidatesProductionConfirmation() {
    var t = cluster("SIMULATED", Map.of("production", true));
    when(repo.confirmedSnapshot("task"))
        .thenReturn(
            Map.of("config", t.config(), "targets", List.of(Map.of("id", "host", "version", 1))));
    assertEquals(
        "CONFIRMATION_STALE",
        assertThrows(
                ApiException.class,
                () ->
                    service.verifyConfirmation(
                        t,
                        List.of(
                            new AssetTarget(
                                "host",
                                "Host",
                                "SERVER",
                                "SIMULATED",
                                "ADMITTED",
                                "READY",
                                2,
                                Map.of()))))
            .code);
  }

  @Test
  void completedReceiptWinsOverLaterTaskVersion() {
    var t = cluster("SIMULATED", Map.of());
    when(repo.task("task", false)).thenReturn(t);
    when(repo.task("task", true)).thenReturn(t);
    Run receipt =
        new Run(
            "run",
            "task",
            "demo",
            1,
            "SIMULATED",
            "SUCCEEDED",
            100,
            Map.of(),
            List.of(),
            null,
            null,
            null);
    when(repo.receipt(anyString(), anyString(), anyString())).thenReturn(Optional.of(receipt));
    assertSame(receipt, service.execute("task", new ExecuteRequest(1L, "request-1234")));
    verifyNoInteractions(assets);
  }

  @Test
  void unknownRunBlocksAutomaticReplay() {
    var t = cluster("SIMULATED", Map.of());
    when(repo.task("task", false)).thenReturn(t);
    when(repo.task("task", true)).thenReturn(t);
    when(repo.receipt(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    when(repo.active("task")).thenReturn(true);
    assertEquals(
        "ACTIVE_RUN",
        assertThrows(
                ApiException.class,
                () -> service.execute("task", new ExecuteRequest(1L, "new-request-1234")))
            .code);
    verifyNoInteractions(assets);
  }

  @Test
  void manualReconciliationRequiresEvidence() {
    Run run =
        new Run(
            "run",
            "task",
            "demo",
            1,
            "SIMULATED",
            "UNKNOWN",
            30,
            Map.of(),
            List.of(),
            null,
            null,
            null);
    when(repo.run("run")).thenReturn(run);
    assertEquals(
        "EVIDENCE_REQUIRED",
        assertThrows(
                ApiException.class,
                () -> service.reconcile("run", new ReconcileRequest("SUCCEEDED", "", "done")))
            .code);
    verify(repo, never()).reconcile(any(), anyString(), any());
  }

  @Test
  void manualEvidenceCannotFabricateRealSuccess() {
    Run run =
        new Run(
            "run", "task", "demo", 1, "REAL", "UNKNOWN", 30, Map.of(), List.of(), null, null, null);
    when(repo.run("run")).thenReturn(run);
    assertEquals(
        "PROBE_NOT_CONFIGURED",
        assertThrows(
                ApiException.class,
                () ->
                    service.reconcile(
                        "run",
                        new ReconcileRequest("SUCCEEDED", "ticket:123", "已人工核对外部系统，申请确认本次执行")))
            .code);
    verify(repo, never()).reconcile(any(), anyString(), any());
  }

  @Test
  void unknownSimulationCannotFabricateCapacityAllocation() {
    Run run =
        new Run(
            "run",
            "task",
            "demo",
            1,
            "SIMULATED",
            "UNKNOWN",
            30,
            Map.of(),
            List.of(),
            null,
            null,
            null);
    when(repo.run("run")).thenReturn(run);
    assertEquals(
        "RESULT_NOT_FOUND",
        assertThrows(
                ApiException.class,
                () ->
                    service.reconcile(
                        "run",
                        new ReconcileRequest("SUCCEEDED", "ticket:123", "已人工核对外部系统，申请确认本次执行")))
            .code);
    verify(repo, never()).reconcile(any(), anyString(), any());
  }
}
