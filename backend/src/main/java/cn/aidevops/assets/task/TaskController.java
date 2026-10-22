package cn.aidevops.assets.task;

import cn.aidevops.assets.task.TaskModels.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/tasks")
  public List<Task> list(
      @RequestParam String projectId, @RequestParam(required = false) String kind) {
    return tasks.list(projectId, kind);
  }

  @GetMapping("/tasks/{id}")
  public Task get(@PathVariable String id) {
    return tasks.get(id);
  }

  @PostMapping("/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  public Task create(@RequestBody SaveRequest request) {
    return tasks.create(request);
  }

  @PutMapping("/tasks/{id}")
  public Task update(@PathVariable String id, @RequestBody SaveRequest request) {
    return tasks.update(id, request);
  }

  @DeleteMapping("/tasks/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id, @RequestParam Long version) {
    tasks.delete(id, version);
  }

  @PostMapping("/tasks/{id}/validate")
  public Validation validate(@PathVariable String id, @RequestBody VersionRequest request) {
    return tasks.validate(id, request.version());
  }

  @PostMapping("/tasks/{id}/confirm")
  public Task confirm(@PathVariable String id, @RequestBody VersionRequest request) {
    return tasks.confirm(id, request.version());
  }

  @PostMapping("/tasks/{id}/execute")
  public Run execute(@PathVariable String id, @RequestBody ExecuteRequest request) {
    return tasks.execute(id, request);
  }

  @GetMapping("/runs")
  public List<Run> runs(@RequestParam String projectId) {
    return tasks.runs(projectId);
  }

  @GetMapping("/runs/{id}")
  public Run run(@PathVariable String id) {
    return tasks.run(id);
  }

  @GetMapping("/runs/{id}/logs")
  public List<Log> logs(@PathVariable String id) {
    return tasks.logs(id);
  }

  @PostMapping("/runs/{id}/rollback")
  public Run rollback(@PathVariable String id, @RequestBody ExecuteRequest request) {
    return tasks.rollback(id, request);
  }

  @PostMapping("/runs/{id}/reconcile")
  public Run reconcile(@PathVariable String id, @RequestBody ReconcileRequest request) {
    return tasks.reconcile(id, request);
  }
}
