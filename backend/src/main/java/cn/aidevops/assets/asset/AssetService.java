package cn.aidevops.assets.asset;

import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService implements AssetsPort {
  static final Set<String> TYPES = Set.of("SERVER", "CLIENT", "NETWORK", "SOFTWARE");
  static final Set<String> MODES = Set.of("REAL", "SIMULATED");
  static final List<String> MUTABLE =
      List.of(
          "name",
          "category",
          "owner",
          "environment",
          "dept",
          "location",
          "purpose",
          "networkScope",
          "ip");
  private final AssetRepository assets;
  private final AssetVersionRepository versions;
  private final AssetPreviewRepository previews;
  private final Access access;
  private final Audit audit;
  private final AssetMonitoring monitoring;

  public AssetService(
      AssetRepository assets,
      AssetVersionRepository versions,
      AssetPreviewRepository previews,
      Access access,
      Audit audit,
      AssetMonitoring monitoring) {
    this.assets = assets;
    this.versions = versions;
    this.previews = previews;
    this.access = access;
    this.audit = audit;
    this.monitoring = monitoring;
  }

  @Transactional(readOnly = true)
  public PageResult<Map<String, Object>> list(
      String projectId, Map<String, String> filters, int page, int pageSize) {
    access.require(projectId, "asset.read");
    if (page < 1 || pageSize < 1 || pageSize > 200)
      throw bad("INVALID_PAGINATION", "page从1开始，pageSize为1至200");
    var result =
        assets.findAll(
            specification(projectId, filters),
            PageRequest.of(page - 1, pageSize, sorting(filters)));
    return new PageResult<>(
        result.getContent().stream().map(this::view).toList(),
        result.getTotalElements(),
        page,
        pageSize);
  }

  @Transactional
  public List<Map<String, Object>> exportRows(String projectId, Map<String, String> filters) {
    access.require(projectId, "asset.read");
    List<Map<String, Object>> rows =
        assets.findAll(specification(projectId, filters), sorting(filters)).stream()
            .map(this::coreView)
            .toList();
    audit.record(
        projectId, "ASSET_EXPORT", projectId, Map.of("count", rows.size(), "filters", filters));
    return rows;
  }

  private Sort sorting(Map<String, String> filters) {
    String key = filters.getOrDefault("sortBy", "updatedAt"),
        order = filters.getOrDefault("sortOrder", "desc");
    if (!Set.of("name", "type", "category", "admission", "dept", "owner", "updatedAt", "createdAt")
            .contains(key)
        || !Set.of("asc", "desc").contains(order)) throw bad("INVALID_SORT", "排序字段或方向不正确");
    if (key.equals("owner")) key = "ownerName";
    return Sort.by("asc".equals(order) ? Sort.Direction.ASC : Sort.Direction.DESC, key)
        .and(Sort.by("id"));
  }

  private Specification<AssetEntity> specification(String projectId, Map<String, String> filters) {
    return (root, query, cb) -> {
      var conditions = new ArrayList<jakarta.persistence.criteria.Predicate>();
      conditions.add(cb.equal(root.get("projectId"), projectId));
      conditions.add(cb.isFalse(root.get("deleted")));
      for (var filter : filters.entrySet()) {
        String value = filter.getValue(), key = filter.getKey();
        if (value == null || value.isBlank() || Set.of("sortBy", "sortOrder").contains(key))
          continue;
        if (key.equals("search")) {
          String needle = value.toLowerCase(Locale.ROOT);
          conditions.add(
              cb.or(
                  List.of("name", "ip", "externalId", "ownerName", "category").stream()
                      .map(field -> cb.greaterThan(cb.locate(cb.lower(root.get(field)), needle), 0))
                      .toArray(jakarta.persistence.criteria.Predicate[]::new)));
        } else {
          if (key.equals("owner")) key = "ownerName";
          conditions.add(cb.equal(root.get(key), value));
        }
      }
      return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String projectId, String id) {
    access.require(projectId, "asset.read");
    return view(load(projectId, id, false));
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> input) {
    String projectId = required(input, "projectId");
    access.require(projectId, "asset.write");
    Map<String, Object> normalized = normalize(input);
    enforceMode(projectId, string(normalized, "mode"));
    checkUnique(
        projectId,
        normalized,
        null,
        assets.findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc(projectId));
    AssetEntity a = new AssetEntity();
    a.id = UUID.randomUUID().toString();
    a.projectId = projectId;
    a.createdAt = Instant.now();
    a.mode = string(normalized, "mode");
    a.type = string(normalized, "type");
    assign(a, normalized);
    a.manualFieldsJson = Jsons.write(Map.of("fields", manualFields(input, true)));
    a.admission = "DRAFT";
    a.resourceStatus = "UNKNOWN";
    a.source = "MANUAL";
    a.updatedAt = Instant.now();
    assets.saveAndFlush(a);
    history(a, "ASSET_CREATED");
    return view(a);
  }

  @Transactional
  public Map<String, Object> update(String id, Map<String, Object> input) {
    String projectId = required(input, "projectId");
    access.require(projectId, "asset.write");
    AssetEntity a = load(projectId, id, true);
    checkVersion(a, input);
    if (input.containsKey("type") && !string(input, "type").equals(a.type))
      throw bad("IMMUTABLE_TYPE", "资产类型不可改变，请新建独立资产");
    if (input.containsKey("mode") && !string(input, "mode").equals(a.mode))
      throw bad("IMMUTABLE_MODE", "模拟与真实资产不能互相转换");
    if (input.containsKey("connectorId") && !string(input, "connectorId").equals(a.connectorId)
        || input.containsKey("externalId") && !string(input, "externalId").equals(a.externalId))
      throw bad("IMMUTABLE_IDENTITY", "已登记外部身份不可通过普通编辑改变");
    Map<String, Object> merged = new LinkedHashMap<>(view(a));
    merged.putAll(input);
    Map<String, Object> normalized = normalize(merged);
    checkUnique(
        projectId,
        normalized,
        a.id,
        assets.findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc(projectId));
    assign(a, normalized);
    Set<String> fields = protectedFields(a);
    if (input.containsKey("manualFields")) fields = manualFields(input, false);
    else {
      for (String field : MUTABLE) if (input.containsKey(field)) fields.add(field);
      for (String structure : List.of("technical", "operation", "lifecycle"))
        if (input.get(structure) instanceof Map<?, ?> t)
          for (Object key : t.keySet()) fields.add(structure + "." + key);
    }
    a.manualFieldsJson = Jsons.write(Map.of("fields", fields));
    touch(a, "ASSET_UPDATED");
    return view(a);
  }

  @Transactional
  public Map<String, Object> transition(String projectId, String id, long version, String action) {
    access.require(projectId, "asset.write");
    AssetEntity a = load(projectId, id, true);
    checkVersion(a, Map.of("version", version));
    if ("admit".equals(action)) {
      if ("INACTIVE".equals(a.admission)) throw bad("INVALID_STATE", "停用资产不能直接入库，请先编辑并重新核对");
      if (!"ADMITTED".equals(a.admission)) {
        a.admission = "ADMITTED";
        touch(a, "ASSET_ADMITTED");
      }
    } else if ("deactivate".equals(action)) {
      if (!"INACTIVE".equals(a.admission)) {
        a.admission = "INACTIVE";
        touch(a, "ASSET_DEACTIVATED");
      }
    } else throw bad("INVALID_ACTION", "无效状态操作");
    return view(a);
  }

  @Transactional
  public Map<String, Object> delete(String projectId, String id, long version) {
    access.require(projectId, "asset.write");
    AssetEntity a = load(projectId, id, true);
    checkVersion(a, Map.of("version", version));
    a.deleted = true;
    a.admission = "INACTIVE";
    touch(a, "ASSET_REMOVED");
    return Map.of("id", id, "deleted", true, "version", a.version);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> history(String projectId, String id) {
    access.require(projectId, "asset.read");
    AssetEntity asset =
        assets.findById(id).orElseThrow(() -> new ApiException(404, "ASSET_NOT_FOUND", "资产不存在"));
    if (!projectId.equals(asset.projectId)) throw new ApiException(404, "ASSET_NOT_FOUND", "资产不存在");
    return versions.findByProjectIdAndAssetIdOrderByCreatedAtDesc(projectId, id).stream()
        .map(
            v -> {
              Map<String, Object> r = new LinkedHashMap<>();
              r.put("id", v.id);
              r.put("version", v.assetVersion);
              r.put("action", v.action);
              r.put("actor", v.actor);
              r.put("createdAt", v.createdAt);
              r.put("snapshot", Jsons.readMap(v.snapshotJson));
              return r;
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> monitor(String projectId, String id, boolean refresh, String mode) {
    access.require(projectId, refresh ? "asset.execute" : "asset.read");
    AssetEntity a = load(projectId, id, false);
    if (refresh) {
      enforceSimulatedAdapter(projectId, mode);
      if (!"SIMULATED".equals(a.mode)) throw bad("SOURCE_MISMATCH", "真实资产不能使用模拟观测");
    }
    return asMap(view(a).get("monitor"));
  }

  @Transactional
  public Map<String, Object> discover(Map<String, Object> input) {
    String projectId = required(input, "projectId");
    access.require(projectId, "asset.execute");
    String mode = string(input, "mode");
    enforceSimulatedAdapter(projectId, mode);
    List<Map<String, Object>> result = new ArrayList<>();
    int created = 0;
    List<AssetEntity> existing =
        assets.findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc(projectId);
    for (Map<String, Object> row : fixtures()) {
      AssetEntity match = identity(existing, "SIMULATED", "fixture", string(row, "externalId"));
      if (match == null) {
        match = fromSource(projectId, row);
        assets.saveAndFlush(match);
        history(match, "ASSET_DISCOVERED_SIMULATED");
        existing = new ArrayList<>(existing);
        existing.add(match);
        created++;
      }
      result.add(coreView(match));
    }
    return Map.of(
        "mode",
        "SIMULATED",
        "source",
        "SIMULATED_FIXTURE",
        "created",
        created,
        "items",
        result,
        "message",
        "模拟发现结果；尚未接入真实资源，不代表真实扫描或健康状态");
  }

  @Transactional
  public Map<String, Object> importPreview(String projectId, List<Map<String, Object>> rows) {
    access.require(projectId, "asset.write");
    return makePreview(projectId, "IMPORT", "REAL", rows);
  }

  @Transactional
  public Map<String, Object> syncPreview(Map<String, Object> input) {
    String projectId = required(input, "projectId");
    access.require(projectId, "asset.execute");
    String mode = string(input, "mode");
    enforceSimulatedAdapter(projectId, mode);
    List<Map<String, Object>> rows = mapList(input.get("rows"));
    if (rows.isEmpty()) {
      rows = fixtures();
      if (input.get("assetIds") instanceof Collection<?> selected && !selected.isEmpty()) {
        Set<String> externalIds = new HashSet<>();
        for (Object id : selected) {
          AssetEntity a = load(projectId, id.toString(), false);
          if (!"SIMULATED".equals(a.mode) || !"fixture".equals(a.connectorId))
            throw bad("SOURCE_MISMATCH", "所选资产不属于模拟fixture来源");
          externalIds.add(a.externalId);
        }
        rows = rows.stream().filter(r -> externalIds.contains(string(r, "externalId"))).toList();
        if (rows.isEmpty()) throw bad("SOURCE_NOT_FOUND", "所选资产没有可用的模拟来源");
      }
    }
    String connector = string(input, "connectorId");
    if (connector.isBlank()) connector = "fixture";
    List<Map<String, Object>> prepared = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> r = new LinkedHashMap<>(row);
      r.put("mode", mode);
      r.put("connectorId", connector);
      prepared.add(r);
    }
    return makePreview(projectId, "SYNC", mode, prepared);
  }

  private Map<String, Object> makePreview(
      String projectId, String kind, String mode, List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw bad("EMPTY_IMPORT", "没有待处理资产");
    if (rows.size() > 5000) throw bad("TOO_MANY_ROWS", "单次最多预览5000行，请拆分处理");
    List<AssetEntity> existing =
        assets.findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc(projectId);
    List<Map<String, Object>> previewRows = new ArrayList<>();
    Set<String> seenExternal = new HashSet<>(),
        seenIp = new HashSet<>(),
        seenSerial = new HashSet<>(),
        seenMac = new HashSet<>();
    int valid = 0;
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> source = rows.get(i);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("rowNumber", i + 1);
      List<String> errors = new ArrayList<>();
      try {
        Map<String, Object> data = normalize(source);
        enforceMode(projectId, string(data, "mode"));
        AssetEntity match = null;
        if ("SYNC".equals(kind)) {
          if (string(data, "externalId").isBlank())
            throw bad("EXTERNAL_ID_REQUIRED", "同步资产必须有外部ID");
          match =
              identity(
                  existing,
                  string(data, "mode"),
                  string(data, "connectorId"),
                  string(data, "externalId"));
        } else checkUnique(projectId, data, null, existing);
        String ext = string(data, "externalId"),
            ip = string(data, "ip"),
            scope = string(data, "networkScope");
        if (!ext.isBlank()
            && !seenExternal.add(
                string(data, "mode") + "|" + string(data, "connectorId") + "|" + ext))
          throw bad("DUPLICATE_EXTERNAL_ID", "同一预览中外部身份重复");
        if (!ip.isBlank() && !seenIp.add(string(data, "mode") + "|" + scope + "|" + ip))
          throw bad("DUPLICATE_IP", "同一网络范围内IP重复");
        var technical = asMap(data.get("technical"));
        String serial = string(technical, "serial").toLowerCase(Locale.ROOT),
            mac = string(technical, "mac").toLowerCase(Locale.ROOT);
        if (!serial.isBlank() && !seenSerial.add(string(data, "mode") + "|" + serial))
          throw bad("DUPLICATE_SERIAL", "同一预览中序列号重复");
        if (!mac.isBlank() && !seenMac.add(string(data, "mode") + "|" + mac))
          throw bad("DUPLICATE_MAC", "同一预览中MAC地址重复");
        entry.put("data", data);
        entry.put("changeType", match == null ? "CREATE" : "UPDATE");
        if (match != null) {
          if (!match.type.equals(string(data, "type")))
            throw bad("SOURCE_TYPE_CONFLICT", "同步来源类型与已有资产类型不符");
          entry.put("id", match.id);
          entry.put("version", match.version);
          Map<String, Object> effective =
              mergeSource(coreView(match), data, protectedFields(match));
          entry.put("effectiveData", effective);
          entry.put("changes", changes(coreView(match), effective));
          entry.put("protectedFields", protectedFields(match));
          checkUnique(projectId, effective, match.id, existing);
        } else {
          entry.put("changes", data);
          entry.put("protectedFields", List.of());
        }
        entry.put("status", "VALID");
        valid++;
      } catch (ApiException e) {
        errors.add(e.getMessage());
        entry.put("data", source);
        entry.put("status", "INVALID");
      }
      entry.put("errors", errors);
      previewRows.add(entry);
    }
    AssetPreviewEntity preview = new AssetPreviewEntity();
    preview.id = UUID.randomUUID().toString();
    preview.projectId = projectId;
    preview.kind = kind;
    preview.actor = access.actor().subject();
    preview.status = "PENDING";
    preview.createdAt = Instant.now();
    preview.expiresAt = preview.createdAt.plus(Duration.ofHours(1));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rows", previewRows);
    Set<String> rowModes =
        previewRows.stream()
            .filter(r -> "VALID".equals(r.get("status")))
            .map(r -> string(asMap(r.get("data")), "mode"))
            .collect(Collectors.toSet());
    payload.put(
        "mode",
        rowModes.size() > 1 ? "MIXED" : rowModes.isEmpty() ? mode : rowModes.iterator().next());
    payload.put("validCount", valid);
    payload.put("errorCount", rows.size() - valid);
    preview.payloadJson = Jsons.write(payload);
    previews.save(preview);
    audit.record(
        projectId,
        "ASSET_" + kind + "_PREVIEW",
        preview.id,
        Map.of("validCount", valid, "errorCount", rows.size() - valid));
    Map<String, Object> result = new LinkedHashMap<>(payload);
    result.put("previewId", preview.id);
    result.put("kind", kind);
    result.put("status", preview.status);
    result.put("expiresAt", preview.expiresAt);
    return result;
  }

  @Transactional
  public Map<String, Object> confirm(String projectId, String previewId, String kind) {
    access.require(projectId, "SYNC".equals(kind) ? "asset.execute" : "asset.write");
    AssetPreviewEntity preview =
        previews
            .locked(previewId)
            .orElseThrow(() -> new ApiException(404, "PREVIEW_NOT_FOUND", "预览不存在"));
    if (!projectId.equals(preview.projectId) || !kind.equals(preview.kind))
      throw new ApiException(404, "PREVIEW_NOT_FOUND", "预览不存在");
    if ("CONFIRMED".equals(preview.status)) return Jsons.readMap(preview.resultJson);
    if (preview.expiresAt.isBefore(Instant.now()))
      throw new ApiException(409, "PREVIEW_EXPIRED", "预览已过期，请重新预览");
    Map<String, Object> payload = Jsons.readMap(preview.payloadJson);
    List<Map<String, Object>> rows = mapList(payload.get("rows"));
    List<AssetEntity> all =
        new ArrayList<>(assets.findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc(projectId));
    for (Map<String, Object> row : rows)
      if ("VALID".equals(row.get("status"))) {
        Map<String, Object> data = asMap(row.getOrDefault("effectiveData", row.get("data")));
        String id = string(row, "id");
        if (!id.isBlank()) {
          AssetEntity a = load(projectId, id, true);
          checkVersion(a, row);
        }
        checkUnique(projectId, data, id.isBlank() ? null : id, all);
      }
    List<Map<String, Object>> applied = new ArrayList<>();
    List<Map<String, Object>> rejected = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      if (!"VALID".equals(row.get("status"))) {
        rejected.add(row);
        continue;
      }
      Map<String, Object> data = asMap(row.getOrDefault("effectiveData", row.get("data")));
      String id = string(row, "id");
      AssetEntity a;
      if (id.isBlank()) {
        a = fromSource(projectId, data);
        if ("IMPORT".equals(kind)) {
          a.admission = "DRAFT";
          a.resourceStatus = "UNKNOWN";
          a.source = "IMPORT";
          a.manualFieldsJson = Jsons.write(Map.of("fields", manualFields(data, true)));
        }
        assets.saveAndFlush(a);
        all.add(a);
      } else {
        a = load(projectId, id, true);
        assign(a, data);
        a.observedAt = Instant.now();
        a.source = "SIMULATED_FIXTURE";
        a.updatedAt = Instant.now();
        assets.saveAndFlush(a);
      }
      history(a, "ASSET_" + kind + "_CONFIRMED");
      applied.add(view(a));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("previewId", preview.id);
    result.put("status", "CONFIRMED");
    result.put("applied", applied.size());
    result.put("rejected", rejected.size());
    result.put("items", applied);
    result.put("rejectedRows", rejected);
    result.put("mode", payload.get("mode"));
    preview.status = "CONFIRMED";
    preview.resultJson = Jsons.write(result);
    previews.save(preview);
    audit.record(
        projectId,
        "ASSET_" + kind + "_CONFIRMED",
        preview.id,
        Map.of("applied", applied.size(), "rejected", rejected.size()));
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public List<AssetTarget> targets(String projectId) {
    return assets.findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc(projectId).stream()
        .map(this::target)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AssetTarget> requireTargets(String projectId, List<String> ids, boolean ready) {
    if (ids == null || ids.isEmpty()) throw bad("TARGETS_REQUIRED", "必须指定目标资产");
    if (new HashSet<>(ids).size() != ids.size()) throw bad("DUPLICATE_TARGETS", "目标资产不能重复");
    List<AssetTarget> result = new ArrayList<>();
    for (String id : ids) {
      AssetEntity a = load(projectId, id, false);
      if (!"ADMITTED".equals(a.admission))
        throw new ApiException(409, "ASSET_NOT_ADMITTED", "资产尚未入库或已经停用：" + a.name);
      if (ready && !"READY".equals(a.resourceStatus))
        throw new ApiException(409, "ASSET_NOT_READY", "目标资源尚未核实就绪：" + a.name);
      result.add(target(a));
    }
    return result;
  }

  private AssetTarget target(AssetEntity a) {
    return new AssetTarget(
        a.id,
        a.name,
        a.type,
        a.mode,
        a.admission,
        a.resourceStatus,
        a.version,
        Jsons.readMap(a.technicalJson));
  }

  Map<String, Object> view(AssetEntity a) {
    Map<String, Object> result = coreView(a);
    result.put("monitor", monitoring.latest(a.projectId, a.id, a.mode));
    return result;
  }

  Map<String, Object> coreView(AssetEntity a) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", a.id);
    r.put("projectId", a.projectId);
    r.put("name", a.name);
    r.put("type", a.type);
    r.put("category", a.category);
    r.put("owner", a.ownerName);
    r.put("environment", a.environment);
    r.put("dept", a.dept);
    r.put("location", a.location);
    r.put("purpose", a.purpose);
    r.put("operation", Jsons.readMap(a.operation));
    r.put("lifecycle", Jsons.readMap(a.lifecycle));
    r.put("mode", a.mode);
    r.put("admission", a.admission);
    r.put("resourceStatus", a.resourceStatus);
    r.put("connectorId", a.connectorId);
    r.put("externalId", a.externalId);
    r.put("networkScope", a.networkScope);
    r.put("ip", a.ip);
    r.put("technical", Jsons.readMap(a.technicalJson));
    r.put("manualFields", protectedFields(a));
    r.put("observedAt", a.observedAt);
    r.put("source", a.source);
    r.put("version", a.version);
    r.put("createdAt", a.createdAt);
    r.put("updatedAt", a.updatedAt);
    return r;
  }

  static Map<String, Object> normalize(Map<String, Object> input) {
    Map<String, Object> r = new LinkedHashMap<>();
    String name = required(input, "name"), type = required(input, "type").toUpperCase(Locale.ROOT);
    if (!TYPES.contains(type)) throw bad("INVALID_TYPE", "type必须为SERVER、CLIENT、NETWORK或SOFTWARE");
    String mode = string(input, "mode");
    if (mode.isBlank()) mode = "REAL";
    if (!MODES.contains(mode)) throw bad("INVALID_MODE", "mode必须为REAL或SIMULATED");
    r.put("name", name);
    r.put("type", type);
    r.put("mode", mode);
    for (String f : MUTABLE) r.put(f, string(input, f));
    for (String f : List.of("connectorId", "externalId")) r.put(f, string(input, f));
    if (!string(r, "externalId").isBlank() && string(r, "connectorId").isBlank())
      throw bad("CONNECTOR_REQUIRED", "有externalId时必须指定connectorId");
    if (!string(r, "ip").isBlank() && string(r, "networkScope").isBlank())
      throw bad("NETWORK_SCOPE_REQUIRED", "填写IP时必须明确networkScope");
    if (name.length() > 200) throw bad("INVALID_NAME", "资产名称不能超过200字");
    for (String field : List.of("connectorId", "externalId", "networkScope", "ip", "category")) {
      int max =
          switch (field) {
            case "externalId" -> 200;
            case "ip" -> 100;
            case "category" -> 120;
            default -> 160;
          };
      if (string(r, field).length() > max) throw bad("FIELD_TOO_LONG", field + "字段过长");
    }
    for (String f : MUTABLE)
      if (string(r, f).length()
          > (f.equals("purpose")
              ? 400
              : f.equals("location") ? 200 : f.equals("environment") ? 120 : 160))
        throw bad("FIELD_TOO_LONG", f + "字段过长");
    for (String field : List.of("technical", "operation", "lifecycle")) {
      if (input.containsKey(field)
          && input.get(field) != null
          && !(input.get(field) instanceof Map<?, ?>))
        throw bad("INVALID_STRUCTURE", field + "必须为对象");
      r.put(field, asMap(input.get(field)));
    }
    Map<String, Object> technical = new LinkedHashMap<>(asMap(r.get("technical")));
    String mac = string(technical, "mac");
    if (!mac.isBlank()) {
      String canonical = mac.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
      if (canonical.length() != 12 || !mac.matches("(?i)[0-9a-f:. -]+"))
        throw bad("MAC_INVALID", "MAC地址格式不正确");
      technical.put(
          "mac", String.join(":", canonical.split("(?<=\\G..)")).toLowerCase(Locale.ROOT));
    }
    if (string(technical, "serial").length() > 200) throw bad("SERIAL_INVALID", "序列号过长");
    r.put("technical", technical);
    if (input.containsKey("manualFields")) r.put("manualFields", input.get("manualFields"));
    return r;
  }

  static Map<String, Object> mergeSource(
      Map<String, Object> existing, Map<String, Object> source, Set<String> protectedFields) {
    Map<String, Object> merged = new LinkedHashMap<>(existing);
    for (String field : MUTABLE) {
      Object value = source.get(field);
      if (!protectedFields.contains(field) && value != null && !value.toString().isBlank())
        merged.put(field, value);
    }
    for (String field : List.of("technical", "operation", "lifecycle")) {
      Map<String, Object> values = new LinkedHashMap<>(asMap(existing.get(field)));
      asMap(source.get(field))
          .forEach(
              (key, value) -> {
                if (!protectedFields.contains(field)
                    && !protectedFields.contains(field + "." + key)
                    && value != null
                    && !(value instanceof String s && s.isBlank())) values.put(key, value);
              });
      merged.put(field, values);
    }
    return merged;
  }

  private static Map<String, Object> changes(
      Map<String, Object> before, Map<String, Object> after) {
    Map<String, Object> diff = new LinkedHashMap<>();
    for (String key : after.keySet())
      if (!Objects.equals(before.get(key), after.get(key)))
        diff.put(
            key,
            Map.of(
                "before",
                Objects.toString(before.get(key), ""),
                "after",
                Objects.toString(after.get(key), "")));
    return diff;
  }

  private static Set<String> manualFields(Map<String, Object> input, boolean defaults) {
    Set<String> fields = new LinkedHashSet<>();
    Object explicit = input.get("manualFields");
    if (explicit != null && !(explicit instanceof Collection<?>))
      throw bad("INVALID_MANUAL_FIELD", "manualFields必须为字段名数组");
    if (explicit instanceof Collection<?> values) {
      for (Object value : values) {
        String field = Objects.toString(value, "");
        if (MUTABLE.contains(field)
            || field.equals("type")
            || List.of("technical", "operation", "lifecycle").stream()
                .anyMatch(prefix -> field.equals(prefix) || field.startsWith(prefix + ".")))
          fields.add(field);
        else throw bad("INVALID_MANUAL_FIELD", "不支持的人工保护字段：" + field);
      }
    } else if (defaults) {
      for (String field : MUTABLE)
        if (input.containsKey(field) && !string(input, field).isBlank()) fields.add(field);
      for (String structure : List.of("technical", "operation", "lifecycle"))
        asMap(input.get(structure)).keySet().forEach(k -> fields.add(structure + "." + k));
    }
    if (fields.contains("ip")) fields.add("networkScope");
    return fields;
  }

  private static Set<String> protectedFields(AssetEntity a) {
    Object fields = Jsons.readMap(a.manualFieldsJson).get("fields");
    if (fields instanceof Collection<?> c)
      return c.stream().map(Object::toString).collect(Collectors.toCollection(LinkedHashSet::new));
    return new LinkedHashSet<>();
  }

  private static void assign(AssetEntity a, Map<String, Object> data) {
    a.name = string(data, "name");
    a.category = string(data, "category");
    a.ownerName = string(data, "owner");
    a.environment = string(data, "environment");
    a.dept = string(data, "dept");
    a.location = string(data, "location");
    a.purpose = string(data, "purpose");
    a.operation = Jsons.write(asMap(data.get("operation")));
    a.lifecycle = Jsons.write(asMap(data.get("lifecycle")));
    a.connectorId = string(data, "connectorId");
    a.externalId = string(data, "externalId");
    a.networkScope = string(data, "networkScope");
    a.ip = string(data, "ip");
    a.technicalJson = Jsons.write(asMap(data.get("technical")));
  }

  private AssetEntity fromSource(String projectId, Map<String, Object> data) {
    AssetEntity a = new AssetEntity();
    a.id = UUID.randomUUID().toString();
    a.projectId = projectId;
    a.type = string(data, "type");
    a.mode = string(data, "mode");
    assign(a, data);
    a.admission = "CANDIDATE";
    a.resourceStatus = "SIMULATED".equals(a.mode) ? "READY" : "UNKNOWN";
    a.source = "SIMULATED".equals(a.mode) ? "SIMULATED_FIXTURE" : "IMPORT";
    a.observedAt = "SIMULATED".equals(a.mode) ? Instant.now() : null;
    a.createdAt = Instant.now();
    a.updatedAt = a.createdAt;
    return a;
  }

  private AssetEntity load(String projectId, String id, boolean lock) {
    AssetEntity a =
        (lock ? assets.locked(id) : assets.findById(id))
            .orElseThrow(() -> new ApiException(404, "ASSET_NOT_FOUND", "资产不存在"));
    if (!projectId.equals(a.projectId) || a.deleted)
      throw new ApiException(404, "ASSET_NOT_FOUND", "资产不存在");
    return a;
  }

  private static void checkVersion(AssetEntity a, Map<String, Object> input) {
    Object value = input.get("version");
    if (value == null) throw bad("VERSION_REQUIRED", "请提交资产version");
    long version;
    try {
      version = Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      throw bad("INVALID_VERSION", "version必须为整数");
    }
    if (a.version != version) throw new ApiException(409, "VERSION_CONFLICT", "资产已被修改，请刷新后重试");
  }

  static void checkUnique(
      String projectId, Map<String, Object> data, String self, List<AssetEntity> all) {
    for (AssetEntity a : all) {
      if (a.deleted
          || Objects.equals(a.id, self)
          || !a.projectId.equals(projectId)
          || !a.mode.equals(string(data, "mode"))) continue;
      String external = string(data, "externalId");
      if (!external.isBlank()
          && external.equals(a.externalId)
          && string(data, "connectorId").equals(a.connectorId))
        throw new ApiException(409, "DUPLICATE_EXTERNAL_ID", "该外部资源已有资产记录：" + a.id);
      if (!"INACTIVE".equals(a.admission)) {
        var prior = Jsons.readMap(a.technicalJson);
        var next = asMap(data.get("technical"));
        String serial = string(next, "serial");
        if (!serial.isBlank() && serial.equalsIgnoreCase(string(prior, "serial")))
          throw new ApiException(409, "DUPLICATE_SERIAL", "序列号与已有资产冲突，请核对物理对象");
        String mac = string(next, "mac").replaceAll("[^0-9a-fA-F]", "");
        if (!mac.isBlank()
            && mac.equalsIgnoreCase(string(prior, "mac").replaceAll("[^0-9a-fA-F]", "")))
          throw new ApiException(409, "DUPLICATE_MAC", "MAC地址与已有资产冲突，请核对物理对象");
      }
      String ip = string(data, "ip");
      if (!ip.isBlank()
          && !"INACTIVE".equals(a.admission)
          && ip.equals(a.ip)
          && string(data, "networkScope").equals(a.networkScope))
        throw new ApiException(409, "DUPLICATE_IP", "该网络范围中的IP已被登记");
    }
  }

  private static AssetEntity identity(
      List<AssetEntity> all, String mode, String connector, String external) {
    return all.stream()
        .filter(
            a ->
                a.mode.equals(mode)
                    && a.connectorId.equals(connector)
                    && a.externalId.equals(external)
                    && !external.isBlank())
        .findFirst()
        .orElse(null);
  }

  private void touch(AssetEntity a, String action) {
    a.updatedAt = Instant.now();
    assets.saveAndFlush(a);
    history(a, action);
  }

  private void history(AssetEntity a, String action) {
    AssetVersionEntity v = new AssetVersionEntity();
    v.id = UUID.randomUUID().toString();
    v.assetId = a.id;
    v.projectId = a.projectId;
    v.assetVersion = a.version;
    v.action = action;
    v.actor = access.actor().subject();
    v.snapshotJson = Jsons.write(view(a));
    v.createdAt = Instant.now();
    versions.save(v);
    audit.record(a.projectId, action, a.id, Map.of("version", a.version, "mode", a.mode));
  }

  private static void enforceMode(String projectId, String mode) {
    if ("SIMULATED".equals(mode) && !"demo".equals(projectId))
      throw bad("SIMULATION_DEMO_ONLY", "模拟资源只允许在demo项目使用");
  }

  private static void enforceSimulatedAdapter(String projectId, String mode) {
    if (!"SIMULATED".equals(mode))
      throw new ApiException(503, "ADAPTER_NOT_CONFIGURED", "真实资源适配器尚未配置；不会执行扫描或资源变更");
    enforceMode(projectId, mode);
  }

  static List<Map<String, Object>> fixtures() {
    List<Map<String, Object>> result = new ArrayList<>();
    String[] types = {"SERVER", "CLIENT", "NETWORK", "SOFTWARE"};
    String[] names = {"研发计算节点（模拟）", "Linux测试终端（模拟）", "核心交换设备（模拟）", "AIDevOps应用（模拟）"};
    for (int i = 0; i < types.length; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", names[i]);
      row.put("type", types[i]);
      row.put("mode", "SIMULATED");
      row.put(
          "category",
          switch (types[i]) {
            case "SERVER" -> "计算服务器";
            case "CLIENT" -> "研发终端";
            case "NETWORK" -> "交换机";
            default -> "平台应用";
          });
      row.put("connectorId", "fixture");
      row.put("externalId", "fixture-" + types[i].toLowerCase(Locale.ROOT));
      row.put("networkScope", "demo-lan");
      row.put("ip", i < 3 ? "192.0.2." + (10 + i) : "");
      row.put("environment", "测试");
      row.put("owner", "演示运维组");
      row.put(
          "technical",
          i == 0
              ? Map.of(
                  "cpu",
                  64,
                  "cpuCores",
                  64,
                  "memoryGb",
                  256,
                  "diskGb",
                  2000,
                  "reservedCpu",
                  0,
                  "reservedMemoryGb",
                  0,
                  "reservedDiskGb",
                  0,
                  "os",
                  "Linux ARM64",
                  "observationMode",
                  "SIMULATED")
              : Map.of("observationMode", "SIMULATED", "os", i == 3 ? "N/A" : "Linux"));
      result.add(normalize(row));
    }
    return result;
  }

  static String string(Map<String, ?> map, String key) {
    return Objects.toString(map.get(key), "").strip();
  }

  static String required(Map<String, ?> map, String key) {
    String value = string(map, key);
    if (value.isBlank()) throw bad("FIELD_REQUIRED", key + "不能为空");
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> m
        ? new LinkedHashMap<>((Map<String, Object>) m)
        : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> mapList(Object value) {
    if (!(value instanceof Collection<?> c)) return new ArrayList<>();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object row : c) {
      if (!(row instanceof Map<?, ?>)) throw bad("INVALID_ROWS", "rows中每项必须为对象");
      result.add(new LinkedHashMap<>((Map<String, Object>) row));
    }
    return result;
  }

  static ApiException bad(String code, String message) {
    return new ApiException(400, code, message);
  }
}
