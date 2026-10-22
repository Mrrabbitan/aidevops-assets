package cn.aidevops.assets.asset;

import cn.aidevops.assets.common.PageResult;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {
  private final AssetService service;
  private final AssetDocuments documents;

  public AssetController(AssetService service, AssetDocuments documents) {
    this.service = service;
    this.documents = documents;
  }

  @GetMapping
  public PageResult<Map<String, Object>> list(
      @RequestParam String projectId,
      @RequestParam Map<String, String> parameters,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return service.list(projectId, filters(parameters), page, pageSize);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @Validated({AssetWriteRequest.Create.class, jakarta.validation.groups.Default.class})
          @RequestBody
          AssetWriteRequest body) {
    return service.create(body.toMap());
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String id, @RequestParam String projectId) {
    return service.get(projectId, id);
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(
      @PathVariable String id, @Valid @RequestBody AssetWriteRequest body) {
    return service.update(id, body.toMap());
  }

  @PostMapping("/{id}/admit")
  public Map<String, Object> admit(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return service.transition(AssetService.required(body, "projectId"), id, version(body), "admit");
  }

  @PostMapping("/{id}/deactivate")
  public Map<String, Object> deactivate(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return service.transition(
        AssetService.required(body, "projectId"), id, version(body), "deactivate");
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(
      @PathVariable String id, @RequestParam String projectId, @RequestParam long version) {
    return service.delete(projectId, id, version);
  }

  @GetMapping("/{id}/versions")
  public List<Map<String, Object>> versions(
      @PathVariable String id, @RequestParam String projectId) {
    return service.history(projectId, id);
  }

  @GetMapping("/{id}/monitor")
  public Map<String, Object> monitor(@PathVariable String id, @RequestParam String projectId) {
    return service.monitor(projectId, id, false, "");
  }

  @PostMapping("/{id}/monitor")
  public Map<String, Object> refreshMonitor(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return service.monitor(
        AssetService.required(body, "projectId"), id, true, AssetService.string(body, "mode"));
  }

  @PostMapping("/discover")
  public Map<String, Object> discover(@RequestBody Map<String, Object> body) {
    return service.discover(body);
  }

  @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> importPreview(
      @RequestParam String projectId, @RequestParam MultipartFile file) throws IOException {
    return service.importPreview(
        projectId, documents.parse(file.getOriginalFilename(), file.getBytes()));
  }

  @PostMapping("/import/confirm")
  public Map<String, Object> importConfirm(@RequestBody Map<String, Object> body) {
    return service.confirm(
        AssetService.required(body, "projectId"),
        AssetService.required(body, "previewId"),
        "IMPORT");
  }

  @PostMapping("/sync/preview")
  public Map<String, Object> syncPreview(@RequestBody Map<String, Object> body) {
    return service.syncPreview(body);
  }

  @PostMapping("/sync/confirm")
  public Map<String, Object> syncConfirm(@RequestBody Map<String, Object> body) {
    return service.confirm(
        AssetService.required(body, "projectId"), AssetService.required(body, "previewId"), "SYNC");
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export(
      @RequestParam String projectId,
      @RequestParam(defaultValue = "csv") String format,
      @RequestParam Map<String, String> parameters) {
    String f = format.toLowerCase(Locale.ROOT);
    byte[] bytes = documents.export(f, service.exportRows(projectId, filters(parameters)));
    MediaType media =
        switch (f) {
          case "csv" -> new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
          case "json" -> MediaType.APPLICATION_JSON;
          default ->
              MediaType.parseMediaType(
                  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        };
    return ResponseEntity.ok()
        .contentType(media)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assets." + f)
        .body(bytes);
  }

  private static Map<String, String> filters(Map<String, String> parameters) {
    Map<String, String> r = new LinkedHashMap<>();
    for (String key :
        List.of(
            "type",
            "category",
            "admission",
            "mode",
            "resourceStatus",
            "environment",
            "dept",
            "lifecycle",
            "owner",
            "search",
            "source",
            "sortBy",
            "sortOrder")) if (parameters.containsKey(key)) r.put(key, parameters.get(key));
    return r;
  }

  private static long version(Map<String, Object> body) {
    String version = AssetService.required(body, "version");
    try {
      return Long.parseLong(version);
    } catch (NumberFormatException e) {
      throw AssetService.bad("INVALID_VERSION", "version必须为整数");
    }
  }
}
