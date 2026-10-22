package cn.aidevops.assets.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssetServiceTest {
  AssetRepository assets = mock(AssetRepository.class);
  AssetVersionRepository versions = mock(AssetVersionRepository.class);
  AssetPreviewRepository previews = mock(AssetPreviewRepository.class);
  Access access = mock(Access.class);
  Audit audit = mock(Audit.class);
  AssetMonitoring monitoring = mock(AssetMonitoring.class);
  AssetService service = new AssetService(assets, versions, previews, access, audit, monitoring);

  @BeforeEach
  void actor() {
    when(access.actor())
        .thenReturn(
            new Access.Actor("operator", Set.of("ops"), Set.of("demo"), Set.of("*"), false));
  }

  @Test
  void editingWithStaleVersionDoesNotWriteOrAudit() {
    AssetEntity a = asset();
    a.version = 7;
    when(assets.locked(a.id)).thenReturn(Optional.of(a));
    ApiException error =
        assertThrows(
            ApiException.class,
            () ->
                service.update(
                    a.id, Map.of("projectId", "demo", "version", 6, "name", "overwritten")));
    assertEquals("VERSION_CONFLICT", error.code);
    verify(assets, never()).saveAndFlush(any());
    verifyNoInteractions(audit);
    assertEquals("host", a.name);
  }

  @Test
  void realAdapterNeverReturnsSimulatedSuccess() {
    ApiException error =
        assertThrows(
            ApiException.class,
            () -> service.discover(Map.of("projectId", "demo", "mode", "REAL")));
    assertEquals(503, error.status);
    assertEquals("ADAPTER_NOT_CONFIGURED", error.code);
    verifyNoInteractions(assets);
  }

  @Test
  void simulationCannotBeCreatedOutsideDemo() {
    ApiException error =
        assertThrows(
            ApiException.class,
            () ->
                service.create(
                    Map.of(
                        "projectId",
                        "sandbox",
                        "name",
                        "host",
                        "type",
                        "SERVER",
                        "mode",
                        "SIMULATED")));
    assertEquals("SIMULATION_DEMO_ONLY", error.code);
    verify(assets, never()).saveAndFlush(any());
  }

  @Test
  void simulatedReadyResourceDoesNotBecomeHealthyMonitoring() {
    AssetEntity a = asset();
    a.mode = "SIMULATED";
    a.resourceStatus = "READY";
    a.source = "SIMULATED_FIXTURE";
    when(assets.findById(a.id)).thenReturn(Optional.of(a));
    when(monitoring.latest("demo", a.id, "SIMULATED"))
        .thenReturn(Map.of("status", "UNKNOWN", "reason", "SIMULATED_SOURCE"));
    var result = service.monitor("demo", a.id, false, "");
    assertEquals("UNKNOWN", result.get("status"));
    assertEquals("SIMULATED_SOURCE", result.get("reason"));
  }

  @Test
  void confirmingStaleSyncPreviewCannotOverwriteUpdatedAsset() {
    AssetEntity a = asset();
    a.version = 9;
    when(assets.locked(a.id)).thenReturn(Optional.of(a));
    when(assets.findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc("demo")).thenReturn(List.of(a));
    AssetPreviewEntity p = new AssetPreviewEntity();
    p.id = "preview";
    p.projectId = "demo";
    p.kind = "SYNC";
    p.status = "PENDING";
    p.expiresAt = Instant.now().plusSeconds(60);
    p.payloadJson =
        Jsons.write(
            Map.of(
                "mode",
                "SIMULATED",
                "rows",
                List.of(
                    Map.of(
                        "status",
                        "VALID",
                        "id",
                        a.id,
                        "version",
                        8,
                        "data",
                        Map.of("name", "source", "type", "SERVER", "mode", "REAL")))));
    when(previews.locked(p.id)).thenReturn(Optional.of(p));
    ApiException error =
        assertThrows(ApiException.class, () -> service.confirm("demo", p.id, "SYNC"));
    assertEquals("VERSION_CONFLICT", error.code);
    verify(assets, never()).saveAndFlush(any());
    assertEquals("PENDING", p.status);
  }

  @Test
  void backgroundPortRequiresAdmittedReadyTargetsWithoutCallerSecurityContext() {
    AssetEntity a = asset();
    a.admission = "ADMITTED";
    a.resourceStatus = "READY";
    when(assets.findById(a.id)).thenReturn(Optional.of(a));
    assertEquals(a.id, service.requireTargets("demo", List.of(a.id), true).getFirst().id());
    verifyNoInteractions(access);
    a.admission = "CANDIDATE";
    assertThrows(ApiException.class, () -> service.requireTargets("demo", List.of(a.id), true));
    assertThrows(ApiException.class, () -> service.requireTargets("sandbox", List.of(a.id), false));
  }

  private AssetEntity asset() {
    AssetEntity a = new AssetEntity();
    a.id = "asset-1";
    a.projectId = "demo";
    a.name = "host";
    a.type = "SERVER";
    a.mode = "REAL";
    a.createdAt = Instant.now();
    a.updatedAt = a.createdAt;
    return a;
  }
}
