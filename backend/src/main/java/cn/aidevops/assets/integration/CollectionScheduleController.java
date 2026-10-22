package cn.aidevops.assets.integration;

import cn.aidevops.assets.integration.CollectionScheduleModels.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CollectionScheduleController {
  private final CollectionScheduleService service;

  public CollectionScheduleController(CollectionScheduleService service) {
    this.service = service;
  }

  @GetMapping("/collection-schedules")
  public List<Plan> list(@RequestParam String projectId) {
    return service.list(projectId);
  }

  @PostMapping("/collection-schedules")
  @ResponseStatus(HttpStatus.CREATED)
  public Plan create(@RequestBody Save request) {
    return service.save(null, request);
  }

  @PutMapping("/collection-schedules/{id}")
  public Plan update(@PathVariable String id, @RequestBody Save request) {
    return service.save(id, request);
  }

  @PostMapping("/collection-schedules/{id}/execute")
  public Run execute(@PathVariable String id, @RequestBody Version request) {
    return service.execute(id, request.version());
  }

  @GetMapping("/collection-runs")
  public List<Run> runs(@RequestParam String projectId) {
    return service.runs(projectId);
  }

  @PostMapping("/collection-runs/{id}/resolve")
  public Run resolve(@PathVariable String id, @RequestBody Resolve request) {
    return service.resolve(id, request);
  }
}
