package cn.aidevops.assets.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.aidevops.assets.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AccessTest {
  @Test
  void adminRoleDoesNotBypassMissingProject() {
    var db = mock(JdbcTemplate.class);
    var access = new Access(db);
    var admin = new Access.Actor("a", Set.of("admin"), Set.of("a"), Set.of("*"), false);
    assertEquals(
        403,
        assertThrows(ApiException.class, () -> access.authorizeSnapshot(admin, "b", "asset.write"))
            .status);
    verifyNoInteractions(db);
  }

  @Test
  void permissionInAnotherProjectDoesNotAuthorizeWrite() {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(anyString(), eq("user"), eq("readonly")))
        .thenReturn(List.of(Map.of("permissions", "[\"asset.read\"]", "service", false)));
    var access = new Access(db);
    var actor =
        new Access.Actor(
            "user",
            Set.of("ops"),
            Set.of("write", "readonly"),
            Set.of("asset.write", "asset.read"),
            false);
    assertEquals(
        403,
        assertThrows(
                ApiException.class,
                () -> access.authorizeSnapshot(actor, "readonly", "asset.write"))
            .status);
  }

  @Test
  void revokedGrantStopsPreviouslyAuthorizedSnapshot() {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(anyString(), eq("u"), eq("demo"))).thenReturn(List.of());
    var access = new Access(db);
    assertThrows(
        ApiException.class,
        () ->
            access.authorizeSnapshot(
                new Access.Actor("u", Set.of(), Set.of("demo"), Set.of("asset.write"), false),
                "demo",
                "asset.write"));
  }

  @Test
  void serviceAndHumanIdentitiesCannotBeInterchanged() {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(anyString(), eq("s"), eq("demo")))
        .thenReturn(List.of(Map.of("permissions", "[\"task.execute\"]", "service", true)));
    var access = new Access(db);
    assertEquals(
        "IDENTITY_KIND_MISMATCH",
        assertThrows(
                ApiException.class,
                () ->
                    access.authorizeSnapshot(
                        new Access.Actor(
                            "s", Set.of(), Set.of("demo"), Set.of("task.execute"), false),
                        "demo",
                        "task.execute"))
            .code);
  }

  @Test
  void internalMcpContextRestoresAfterFailure() {
    var access = new Access(mock(JdbcTemplate.class));
    var actor = new Access.Actor("u", Set.of(), Set.of("demo"), Set.of(), false);
    assertThrows(
        IllegalStateException.class,
        () ->
            access.runAs(
                actor,
                () -> {
                  assertEquals(actor, access.actor());
                  throw new IllegalStateException();
                }));
    assertNull(Access.internalActor());
  }
}
