package cn.aidevops.assets.alert;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AlertController {
  private final AlertService service;

  public AlertController(AlertService s) {
    service = s;
  }

  @GetMapping("/alarm-rules")
  public Object rules(@RequestParam String projectId) {
    return service.rules(projectId);
  }

  @PostMapping("/alarm-rules")
  public Object create(@RequestBody Map<String, Object> b) {
    return service.saveRule(null, b);
  }

  @PutMapping("/alarm-rules/{id}")
  public Object update(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return service.saveRule(id, b);
  }

  @DeleteMapping("/alarm-rules/{id}")
  public Object delete(
      @PathVariable String id, @RequestParam String projectId, @RequestParam long version) {
    return service.deleteRule(id, projectId, version);
  }

  @PostMapping("/alarm-rules/{id}/test")
  public Object test(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return service.test(id, AlertService.text(b, "projectId", ""));
  }

  @GetMapping("/alarms")
  public Object alarms(
      @RequestParam String projectId, @RequestParam(defaultValue = "") String status) {
    return service.alarms(projectId, status);
  }

  @GetMapping("/alarms/{id}")
  public Object detail(@PathVariable String id, @RequestParam String projectId) {
    return service.detail(id, projectId);
  }

  @PostMapping("/alarms/{id}/handle")
  public Object handle(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return service.handle(id, b);
  }

  @PostMapping("/observations")
  public Object observe(@RequestBody Map<String, Object> b) {
    return service.observation(b);
  }

  @GetMapping("/notifications")
  public Object notifications(@RequestParam String projectId) {
    return service.notifications(projectId);
  }

  @PostMapping("/notifications/{id}/read")
  public Object read(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return service.read(id, AlertService.text(b, "projectId", ""));
  }
}
