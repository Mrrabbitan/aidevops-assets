package cn.aidevops.assets.integration;

import cn.aidevops.assets.alert.AlertService;
import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RelationshipController {
  private final Access access;
  private final AssetsPort assets;
  private final JdbcTemplate jdbc;
  private final Audit audit;

  public RelationshipController(Access a, AssetsPort s, JdbcTemplate j, Audit u) {
    access = a;
    assets = s;
    jdbc = j;
    audit = u;
  }

  @GetMapping("/relationships")
  public Object list(
      @RequestParam String projectId, @RequestParam(defaultValue = "") String assetId) {
    access.require(projectId, "asset.read");
    return jdbc
        .queryForList(
            "select r.*,s.name as source_name,t.name as target_name from asset_relationships r join"
                + " assets s on s.id=r.source_id join assets t on t.id=r.target_id where"
                + " r.project_id=? and (?='' or r.source_id=? or r.target_id=?) order by"
                + " r.created_at desc",
            projectId,
            assetId,
            assetId,
            assetId)
        .stream()
        .map(AlertService::view)
        .toList();
  }

  @PostMapping("/relationships")
  @Transactional
  public Object create(@RequestBody Map<String, Object> b) {
    String p = Objects.toString(b.get("projectId"), ""),
        source = Objects.toString(b.get("sourceId"), ""),
        target = Objects.toString(b.get("targetId"), ""),
        type = Objects.toString(b.get("type"), "");
    access.require(p, "asset.write");
    if (source.equals(target)
        || !Set.of("MEMBER_OF", "HOSTED_ON", "DEPLOYED_TO", "USES", "DEPENDS_ON").contains(type))
      throw new ApiException(400, "RELATION_INVALID", "关系类型无效或指向自身");
    var nodes = assets.requireTargets(p, List.of(source, target), false);
    if (nodes.size() != 2 || !nodes.get(0).mode().equals(nodes.get(1).mode()))
      throw new ApiException(400, "MODE_MISMATCH", "关系双方必须为同项目同模式资产");
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into asset_relationships(id,project_id,source_id,target_id,type,mode) values"
            + " (?,?,?,?,?,?)",
        id,
        p,
        source,
        target,
        type,
        nodes.getFirst().mode());
    audit.record(
        p,
        "asset.relation.created",
        id,
        Map.of("sourceId", source, "targetId", target, "type", type));
    return AlertService.view(jdbc.queryForMap("select * from asset_relationships where id=?", id));
  }

  @DeleteMapping("/relationships/{id}")
  @Transactional
  public Object delete(
      @PathVariable String id, @RequestParam String projectId, @RequestParam long version) {
    access.require(projectId, "asset.write");
    if (jdbc.update(
            "delete from asset_relationships where id=? and project_id=? and version=?",
            id,
            projectId,
            version)
        != 1) throw new ApiException(409, "VERSION_CONFLICT", "关系不存在或版本已变化");
    audit.record(projectId, "asset.relation.deleted", id, Map.of("version", version));
    return Map.of("deleted", true);
  }

  @GetMapping("/model/catalog")
  public Object catalog(@RequestParam String projectId) {
    access.require(projectId, "asset.read");
    return List.of(
        Map.of(
            "concepts",
            List.of("数字资产", "硬件资产", "软件资产", "服务器", "客户端", "网络设备", "硬件规格", "资产管理系统", "资产清单"),
            "implementation",
            "assets + technical/operation/lifecycle + asset_relationships",
            "api",
            "/assets"),
        Map.of(
            "concepts",
            List.of("计算集群", "PVE集群", "PVE节点", "虚拟机", "K8S集群", "K8S节点"),
            "implementation",
            "tasks(CLUSTER/VM) + targetIds/physicalAssetIds/hostId/clusterId +"
                + " task_simulated_resources",
            "api",
            "/tasks"),
        Map.of(
            "concepts",
            List.of("容器平台", "微服务框架", "Entando", "客户端操作系统", "热更新服务"),
            "implementation",
            "tasks(ENVIRONMENT) + typed config + dependencies",
            "api",
            "/tasks"),
        Map.of(
            "concepts",
            List.of("部署计划", "部署目标", "应用", "分发渠道", "Flatpak", "Flutter", "热更新包"),
            "implementation",
            "tasks(DEPLOYMENT/DISTRIBUTION) + immutable task_runs/targets + artifact reference",
            "api",
            "/tasks,/runs"),
        Map.of(
            "concepts",
            List.of("监控数据", "清单同步", "告警通知"),
            "implementation",
            "observations + asset_previews + alarms + notifications",
            "api",
            "/observations,/assets/sync,/alarms"),
        Map.of("regulationMapping", "待取得有效上级规制模型及组件版本后逐项核对；本地概念覆盖不表示已完成上级模型联调"));
  }
}
