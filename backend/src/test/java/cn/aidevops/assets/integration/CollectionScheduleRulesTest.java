package cn.aidevops.assets.integration;

import static org.junit.jupiter.api.Assertions.*;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.integration.CollectionScheduleModels.Save;
import java.util.*;
import org.junit.jupiter.api.Test;

class CollectionScheduleRulesTest {
  Save discovery(Boolean enabled, String mode, String project) {
    return new Save(
        project,
        "采集计划",
        "DISCOVERY",
        mode,
        null,
        enabled,
        null,
        null,
        "asset-scheduler-demo",
        Map.of("connectorId", "fixture", "networkScope", "demo-lan"));
  }

  @Test
  void scheduleIsDisabledByDefault() {
    var r = CollectionScheduleRules.normalize(discovery(null, "SIMULATED", "demo"));
    assertFalse(r.enabled());
    assertEquals(60, r.intervalMinutes());
    assertEquals("Asia/Shanghai", r.timezone());
  }

  @Test
  void nullEnumsAreValidationErrors() {
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    CollectionScheduleRules.normalize(
                        new Save(
                            "demo", "plan", null, null, null, null, null, null, "svc", Map.of())))
            .status);
  }

  @Test
  void simulationRequiresIsolatedProject() {
    assertEquals(
        "SIMULATION_ISOLATION",
        assertThrows(
                ApiException.class,
                () -> CollectionScheduleRules.normalize(discovery(false, "SIMULATED", "sandbox")))
            .code);
  }

  @Test
  void realPlanCanBeSavedDisabledButCannotBeEnabled() {
    assertFalse(CollectionScheduleRules.normalize(discovery(false, "REAL", "demo")).enabled());
    assertEquals(
        "NOT_CONFIGURED",
        assertThrows(
                ApiException.class,
                () -> CollectionScheduleRules.normalize(discovery(true, "REAL", "demo")))
            .code);
  }

  @Test
  void syncCannotDefaultToWholePool() {
    var request =
        new Save(
            "demo",
            "sync",
            "SYNC",
            "SIMULATED",
            null,
            false,
            60,
            "Asia/Shanghai",
            "service",
            Map.of("connectorId", "fixture"));
    assertEquals(
        "SCOPE_REQUIRED",
        assertThrows(ApiException.class, () -> CollectionScheduleRules.normalize(request)).code);
  }

  @Test
  void syncRequiresUniqueBoundedExplicitIds() {
    var request =
        new Save(
            "demo",
            "sync",
            "SYNC",
            "SIMULATED",
            null,
            false,
            60,
            "Asia/Shanghai",
            "service",
            Map.of("connectorId", "fixture", "assetIds", List.of("id", "id")));
    assertEquals(
        "SCOPE_REQUIRED",
        assertThrows(ApiException.class, () -> CollectionScheduleRules.normalize(request)).code);
  }

  @Test
  void configCannotOverrideFrozenScopeWithArbitraryRows() {
    var request =
        new Save(
            "demo",
            "sync",
            "SYNC",
            "SIMULATED",
            null,
            false,
            60,
            "Asia/Shanghai",
            "service",
            Map.of(
                "connectorId",
                "fixture",
                "assetIds",
                List.of("id"),
                "rows",
                List.of(Map.of("name", "other"))));
    assertEquals(
        "SCOPE_INVALID",
        assertThrows(ApiException.class, () -> CollectionScheduleRules.normalize(request)).code);
  }

  @Test
  void unknownNetworkIsNotAnApprovedFixtureScope() {
    var request =
        new Save(
            "demo",
            "scan",
            "DISCOVERY",
            "SIMULATED",
            null,
            true,
            60,
            "Asia/Shanghai",
            "service",
            Map.of("connectorId", "fixture", "networkScope", "entire-network"));
    assertEquals(
        "SCOPE_NOT_SUPPORTED",
        assertThrows(ApiException.class, () -> CollectionScheduleRules.normalize(request)).code);
  }

  @Test
  void independentServiceIdentityIsMandatory() {
    var request =
        new Save(
            "demo",
            "scan",
            "DISCOVERY",
            "SIMULATED",
            null,
            false,
            60,
            "Asia/Shanghai",
            "",
            Map.of("connectorId", "fixture", "networkScope", "demo-lan"));
    assertEquals(
        "SERVICE_REQUIRED",
        assertThrows(ApiException.class, () -> CollectionScheduleRules.normalize(request)).code);
  }

  @Test
  void timezoneAndIntervalAreValidated() {
    var request =
        new Save(
            "demo",
            "scan",
            "DISCOVERY",
            "SIMULATED",
            null,
            false,
            0,
            "Asia/Shanghai",
            "svc",
            Map.of());
    assertEquals(
        "VALIDATION",
        assertThrows(ApiException.class, () -> CollectionScheduleRules.normalize(request)).code);
    var zone =
        new Save(
            "demo",
            "scan",
            "DISCOVERY",
            "SIMULATED",
            null,
            false,
            60,
            "no-such-zone",
            "svc",
            Map.of());
    assertEquals(
        "TIMEZONE_INVALID",
        assertThrows(ApiException.class, () -> CollectionScheduleRules.normalize(zone)).code);
  }
}
