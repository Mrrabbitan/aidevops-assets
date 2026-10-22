package cn.aidevops.assets.asset;

import static org.junit.jupiter.api.Assertions.*;

import cn.aidevops.assets.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssetDomainTest {
  @Test
  void identityUsesExternalIdAndConnectorRatherThanName() {
    AssetEntity a = asset("one", "same", "REAL", "pve", "node-1", "lan-a", "10.0.0.1");
    Map<String, Object> another =
        new LinkedHashMap<>(
            AssetService.normalize(
                Map.of(
                    "name",
                    "same",
                    "type",
                    "SERVER",
                    "mode",
                    "REAL",
                    "connectorId",
                    "pve",
                    "externalId",
                    "node-2",
                    "networkScope",
                    "lan-a",
                    "ip",
                    "10.0.0.2")));
    assertDoesNotThrow(() -> AssetService.checkUnique("project", another, null, List.of(a)));
    another.put("externalId", "node-1");
    ApiException error =
        assertThrows(
            ApiException.class,
            () -> AssetService.checkUnique("project", another, null, List.of(a)));
    assertEquals("DUPLICATE_EXTERNAL_ID", error.code);
  }

  @Test
  void identicalIpInDifferentNetworksIsAllowedAndSimulationIsIsolated() {
    AssetEntity a = asset("one", "host", "REAL", "pve", "node-1", "lan-a", "10.0.0.1");
    Map<String, Object> different =
        AssetService.normalize(
            Map.of(
                "name",
                "other",
                "type",
                "SERVER",
                "mode",
                "REAL",
                "networkScope",
                "lan-b",
                "ip",
                "10.0.0.1"));
    assertDoesNotThrow(() -> AssetService.checkUnique("project", different, null, List.of(a)));
    different.put("networkScope", "lan-a");
    assertThrows(
        ApiException.class, () -> AssetService.checkUnique("project", different, null, List.of(a)));
    different.put("mode", "SIMULATED");
    assertDoesNotThrow(() -> AssetService.checkUnique("project", different, null, List.of(a)));
  }

  @Test
  void sourceMergePreservesManualAndUnknownFields() {
    Map<String, Object> current =
        new LinkedHashMap<>(
            Map.of(
                "name",
                "manual name",
                "category",
                "verified",
                "owner",
                "team",
                "technical",
                Map.of("cpu", 64, "os", "verified Linux", "memoryGb", 256)));
    Map<String, Object> incoming =
        Map.of(
            "name",
            "source name",
            "category",
            "",
            "owner",
            "source team",
            "technical",
            new LinkedHashMap<>(Map.of("cpu", 32, "os", "", "memoryGb", 512)));
    Map<String, Object> merged =
        AssetService.mergeSource(current, incoming, Set.of("name", "technical.cpu"));
    assertEquals("manual name", merged.get("name"));
    assertEquals("verified", merged.get("category"));
    assertEquals("source team", merged.get("owner"));
    Map<String, Object> tech = AssetService.asMap(merged.get("technical"));
    assertEquals(64, tech.get("cpu"));
    assertEquals("verified Linux", tech.get("os"));
    assertEquals(512, tech.get("memoryGb"));
  }

  @Test
  void invalidIdentityAndTypeAreRejected() {
    assertThrows(
        ApiException.class,
        () ->
            AssetService.normalize(
                Map.of("name", "host", "type", "SERVER", "externalId", "node-1")));
    assertThrows(
        ApiException.class,
        () -> AssetService.normalize(Map.of("name", "host", "type", "SERVER", "ip", "10.0.0.1")));
    assertThrows(
        ApiException.class, () -> AssetService.normalize(Map.of("name", "host", "type", "VM")));
  }

  @Test
  void fixtureContainsFourTypesAndExplicitSimulationProvenance() {
    var rows = AssetService.fixtures();
    assertEquals(4, rows.size());
    assertEquals(
        AssetService.TYPES,
        new HashSet<>(rows.stream().map(r -> r.get("type").toString()).toList()));
    assertTrue(
        rows.stream()
            .allMatch(
                r -> "SIMULATED".equals(r.get("mode")) && "fixture".equals(r.get("connectorId"))));
  }

  @Test
  void duplicateHardwareSerialAndMacAreRejectedAcrossInputForms() {
    var prior = asset("physical", "node", "REAL", "", "", "", "");
    prior.technicalJson = "{\"serial\":\"Device-001\",\"mac\":\"aa:bb:cc:dd:ee:ff\"}";
    var serial =
        AssetService.normalize(
            Map.of(
                "name",
                "another",
                "type",
                "SERVER",
                "mode",
                "REAL",
                "technical",
                Map.of("serial", "device-001")));
    assertEquals(
        "DUPLICATE_SERIAL",
        assertThrows(
                ApiException.class,
                () -> AssetService.checkUnique("project", serial, null, List.of(prior)))
            .code);
    var mac =
        AssetService.normalize(
            Map.of(
                "name",
                "another",
                "type",
                "NETWORK",
                "mode",
                "REAL",
                "technical",
                Map.of("mac", "AA-BB-CC-DD-EE-FF")));
    assertEquals("aa:bb:cc:dd:ee:ff", AssetService.asMap(mac.get("technical")).get("mac"));
    assertEquals(
        "DUPLICATE_MAC",
        assertThrows(
                ApiException.class,
                () -> AssetService.checkUnique("project", mac, null, List.of(prior)))
            .code);
  }

  @Test
  void malformedMacIsRejectedRatherThanStored() {
    assertEquals(
        "MAC_INVALID",
        assertThrows(
                ApiException.class,
                () ->
                    AssetService.normalize(
                        Map.of(
                            "name", "n", "type", "NETWORK", "technical", Map.of("mac", "unknown"))))
            .code);
  }

  private static AssetEntity asset(
      String id,
      String name,
      String mode,
      String connector,
      String external,
      String scope,
      String ip) {
    AssetEntity a = new AssetEntity();
    a.id = id;
    a.projectId = "project";
    a.name = name;
    a.mode = mode;
    a.connectorId = connector;
    a.externalId = external;
    a.networkScope = scope;
    a.ip = ip;
    a.admission = "ADMITTED";
    return a;
  }
}
