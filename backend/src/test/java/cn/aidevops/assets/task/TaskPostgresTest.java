package cn.aidevops.assets.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import cn.aidevops.assets.task.TaskModels.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL checks. A dedicated database ending in _test is mandatory; never uses application
 * data.
 */
@EnabledIfEnvironmentVariable(named = "ASSETS_TEST_JDBC_URL", matches = ".+")
class TaskPostgresTest {
  static DriverManagerDataSource source;
  JdbcTemplate db;
  TaskRepository repo;
  Access access;
  Audit audit;
  AssetsPort assets;
  TaskService service;
  Access.Actor actor =
      new Access.Actor(
          "task-test-ops",
          Set.of("ops"),
          Set.of("demo"),
          Set.of("task.execute", "task.write", "deployment.confirm"),
          false);

  @BeforeAll
  static void database() {
    source =
        new DriverManagerDataSource(
            System.getenv("ASSETS_TEST_JDBC_URL"),
            System.getenv().getOrDefault("ASSETS_TEST_JDBC_USER", "assets"),
            System.getenv().getOrDefault("ASSETS_TEST_JDBC_PASSWORD", "assets_dev_password"));
    String name =
        new JdbcTemplate(source).queryForObject("select current_database()", String.class);
    assertNotNull(name);
    assertTrue(
        name.endsWith("_test"), "Integration checks require a dedicated database ending in _test");
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
  }

  @BeforeEach
  void setup() {
    db = new JdbcTemplate(source);
    db.execute(
        "TRUNCATE task_run_logs,task_run_targets,task_simulated_resources,task_runs,tasks RESTART"
            + " IDENTITY CASCADE");
    db.update(
        "INSERT INTO access_grants(principal_id,project_id,permissions,enabled,service) VALUES"
            + " ('task-test-ops','demo',?,true,false) ON CONFLICT(principal_id,project_id) DO"
            + " UPDATE SET permissions=excluded.permissions,enabled=true,service=false",
        Jsons.write(actor.permissions()));
    repo =
        new TaskRepository(db, new TransactionTemplate(new DataSourceTransactionManager(source)));
    access = new Access(db);
    audit = new Audit(db);
    assets = mock(AssetsPort.class);
    service = new TaskService(repo, access, assets, audit);
  }

  Task task(String name) {
    return repo.insert(
        new SaveRequest(
            "demo",
            name,
            "CLUSTER",
            "PVE",
            "SIMULATED",
            null,
            new LinkedHashMap<>(
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
                    "env:PVE"))),
        actor.subject());
  }

  AssetTarget host(long version) {
    return new AssetTarget(
        "host",
        "Host",
        "SERVER",
        "SIMULATED",
        "ADMITTED",
        "READY",
        version,
        Map.of("cpuCores", 64, "memoryGb", 128, "diskGb", 2000));
  }

  String queued(Task task, String key) {
    return repo.enqueue(
        task,
        TaskService.snapshot(task, List.of(host(1))),
        actor,
        key,
        TaskService.hash(key),
        TaskService.targetMaps(List.of(host(1))));
  }

  @Test
  void skipLockedDoesNotDoubleClaimBlockedRows() throws Exception {
    String first = queued(task("first"), "first-request");
    String second = queued(task("second"), "second-request");
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("SELECT id FROM task_runs WHERE id=?::uuid FOR UPDATE")) {
        statement.setString(1, first);
        statement.executeQuery().close();
      }
      Lease lease = repo.claim("worker-2").orElseThrow();
      assertEquals(second, lease.runId());
      connection.rollback();
    }
    assertEquals(first, repo.claim("worker-1").orElseThrow().runId());
    assertTrue(repo.claim("worker-3").isEmpty());
  }

  @Test
  void fencingTokenRejectsOldWorkerAndExpiredLeaseNeverReplays() {
    String id = queued(task("recovery"), "recovery-request");
    Lease lease = repo.claim("worker").orElseThrow();
    var stale = new Lease(id, "worker", lease.fencingToken() - 1, lease.actor());
    assertFalse(repo.heartbeat(stale));
    assertEquals(
        "LEASE_LOST",
        assertThrows(ApiException.class, () -> repo.target(stale, "host", "SUCCEEDED", Map.of()))
            .code);
    db.update("UPDATE task_runs SET lease_until=now()-interval '1 second' WHERE id=?::uuid", id);
    assertEquals(1, repo.recoverExpired());
    assertEquals("UNKNOWN", repo.run(id).status());
    assertTrue(repo.active(repo.run(id).taskId()));
    assertTrue(repo.claim("new-worker").isEmpty());
    assertFalse(repo.heartbeat(lease));
  }

  @Test
  void revokedCurrentDatabaseGrantPreventsWorkerSideEffects() {
    String id = queued(task("revoked"), "revoked-request");
    Lease lease = repo.claim("worker").orElseThrow();
    db.update("UPDATE access_grants SET enabled=false WHERE principal_id=?", actor.subject());
    TaskExecutionAdapter adapter = mock(TaskExecutionAdapter.class);
    when(adapter.supports("SIMULATED")).thenReturn(true);
    new TaskWorker(repo, service, access, assets, audit, List.of(adapter)).perform(lease);
    assertEquals("FAILED", repo.run(id).status());
    verify(adapter, never()).execute(any(), any());
    verifyNoInteractions(assets);
    assertEquals(
        0, db.queryForObject("SELECT count(*) FROM task_simulated_resources", Integer.class));
  }

  @Test
  void changedTargetVersionPreventsExecutingFrozenIntent() {
    String id = queued(task("changed"), "changed-request");
    Lease lease = repo.claim("worker").orElseThrow();
    when(assets.requireTargets("demo", List.of("host"), true)).thenReturn(List.of(host(2)));
    TaskExecutionAdapter adapter = mock(TaskExecutionAdapter.class);
    when(adapter.supports("SIMULATED")).thenReturn(true);
    new TaskWorker(repo, service, access, assets, audit, List.of(adapter)).perform(lease);
    assertEquals("FAILED", repo.run(id).status());
    verify(adapter, never()).execute(any(), any());
  }

  @Test
  void immutableSnapshotAndConflictingIdempotencyKey() {
    var t = task("frozen");
    String id = queued(t, "immutable-request");
    t.config().put("platformVersion", "changed-later");
    assertEquals("8", TaskRules.map(repo.run(id).snapshot().get("config")).get("platformVersion"));
    assertEquals(
        id,
        repo.receipt("demo", "immutable-request", TaskService.hash("immutable-request"))
            .orElseThrow()
            .id());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                ApiException.class,
                () -> repo.receipt("demo", "immutable-request", TaskService.hash("different-body")))
            .code);
  }

  @Test
  void successfulSimulationWritesOnlyIsolatedOutput() {
    String id = queued(task("simulation"), "simulation-request");
    Lease lease = repo.claim("worker").orElseThrow();
    when(assets.requireTargets("demo", List.of("host"), true)).thenReturn(List.of(host(1)));
    new TaskWorker(repo, service, access, assets, audit, List.of(new SimulationTaskAdapter()))
        .perform(lease);
    assertEquals("SUCCEEDED", repo.run(id).status());
    assertEquals("SUCCEEDED", repo.run(id).targets().getFirst().status());
    var state =
        Jsons.readMap(
            db.queryForObject(
                "SELECT state FROM task_simulated_resources WHERE task_id=?::uuid",
                String.class,
                repo.run(id).taskId()));
    assertEquals("SIMULATED", state.get("mode"));
    assertEquals(false, state.get("observed"));
  }
}
