package cn.aidevops.assets.mcp;

import cn.aidevops.assets.asset.AssetService;
import cn.aidevops.assets.common.*;
import cn.aidevops.assets.security.Access;
import cn.aidevops.assets.task.TaskModels;
import cn.aidevops.assets.task.TaskService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
public class McpConfiguration {
  @Bean
  HttpServletStreamableServerTransportProvider transport(Access access) {
    return HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .contextExtractor(request -> McpTransportContext.create(Map.of("actor", access.actor())))
        .build();
  }

  @Bean
  ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
      HttpServletStreamableServerTransportProvider transport) {
    return new ServletRegistrationBean<>(transport, "/mcp");
  }

  @Bean(destroyMethod = "close")
  McpSyncServer mcpServer(
      HttpServletStreamableServerTransportProvider transport,
      Access access,
      AssetService assets,
      TaskService tasks,
      Audit audit) {
    var server =
        McpServer.sync(transport)
            .serverInfo("aidevops-assets", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .build();
    register(
        server,
        access,
        audit,
        "assets_search",
        "按项目查询有权访问的资产",
        Map.of("projectId", "string", "search", "string"),
        List.of("projectId"),
        a ->
            assets.list(
                required(a, "projectId"),
                Map.of("search", Objects.toString(a.get("search"), "")),
                1,
                50));
    register(
        server,
        access,
        audit,
        "assets_detail",
        "读取资产详情",
        Map.of("projectId", "string", "id", "string"),
        List.of("projectId", "id"),
        a -> assets.get(required(a, "projectId"), required(a, "id")));
    register(
        server,
        access,
        audit,
        "assets_create",
        "登记资产；登记不代表资源创建或就绪",
        Map.of(
            "projectId",
            "string",
            "name",
            "string",
            "type",
            "string",
            "mode",
            "string",
            "technical",
            "object"),
        List.of("projectId", "name", "type", "mode"),
        assets::create);
    register(
        server,
        access,
        audit,
        "assets_sync_preview",
        "生成同步差异预览，不直接改写资产",
        Map.of("projectId", "string", "mode", "string", "connectorId", "string", "rows", "array"),
        List.of("projectId", "mode", "rows"),
        assets::syncPreview);
    register(
        server,
        access,
        audit,
        "assets_sync_apply",
        "确认当前版本的同步预览；沿用HTTP权限和并发检查",
        Map.of("projectId", "string", "previewId", "string"),
        List.of("projectId", "previewId"),
        a -> assets.confirm(required(a, "projectId"), required(a, "previewId"), "SYNC"));
    register(
        server,
        access,
        audit,
        "tasks_list",
        "查询项目任务",
        Map.of("projectId", "string", "kind", "string"),
        List.of("projectId"),
        a -> tasks.list(required(a, "projectId"), Objects.toString(a.get("kind"), null)));
    register(
        server,
        access,
        audit,
        "tasks_execute",
        "执行已配置且获准任务，返回运行回执；生产计划必须先经受控确认",
        Map.of(
            "projectId",
            "string",
            "id",
            "string",
            "version",
            "integer",
            "idempotencyKey",
            "string"),
        List.of("projectId", "id", "version", "idempotencyKey"),
        a -> {
          var t = tasks.get(required(a, "id"));
          if (!t.projectId().equals(required(a, "projectId")))
            throw new ApiException(403, "FORBIDDEN", "任务项目不符");
          return tasks.execute(
              t.id(),
              new TaskModels.ExecuteRequest(
                  ((Number) a.get("version")).longValue(), required(a, "idempotencyKey")));
        });
    register(
        server,
        access,
        audit,
        "runs_status",
        "查询执行及逐目标状态",
        Map.of("projectId", "string", "id", "string"),
        List.of("projectId", "id"),
        a -> {
          var run = tasks.run(required(a, "id"));
          if (!run.projectId().equals(required(a, "projectId")))
            throw new ApiException(403, "FORBIDDEN", "运行项目不符");
          return run;
        });
    register(
        server,
        access,
        audit,
        "runs_logs",
        "查询执行日志",
        Map.of("projectId", "string", "id", "string"),
        List.of("projectId", "id"),
        a -> {
          var run = tasks.run(required(a, "id"));
          if (!run.projectId().equals(required(a, "projectId")))
            throw new ApiException(403, "FORBIDDEN", "运行项目不符");
          return tasks.logs(run.id());
        });
    return server;
  }

  private static void register(
      McpSyncServer server,
      Access access,
      Audit audit,
      String name,
      String description,
      Map<String, String> fields,
      List<String> required,
      Function<Map<String, Object>, Object> work) {
    Map<String, Object> props = new LinkedHashMap<>();
    fields.forEach(
        (k, v) ->
            props.put(
                k,
                v.equals("array")
                    ? Map.of("type", v, "items", Map.of("type", "object"))
                    : Map.of("type", v)));
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "properties",
            props,
            "required",
            required,
            "additionalProperties",
            false);
    var tool = McpSchema.Tool.builder(name).description(description).inputSchema(schema).build();
    server.addTool(
        new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
              try {
                var actor = (Access.Actor) exchange.transportContext().get("actor");
                if (actor == null) throw new ApiException(401, "UNAUTHENTICATED", "缺少工具调用身份");
                var args = request.arguments();
                if (args == null || !fields.keySet().containsAll(args.keySet()))
                  throw new ApiException(400, "VALIDATION_ERROR", "工具参数不正确");
                for (String key : required)
                  if (args.get(key) == null)
                    throw new ApiException(400, "VALIDATION_ERROR", "缺少参数：" + key);
                Object value =
                    access.runAs(
                        actor,
                        () -> {
                          var result = work.apply(args);
                          audit.record(
                              required(args, "projectId"),
                              "mcp." + name,
                              Objects.toString(args.get("id"), name),
                              Map.of("tool", name));
                          return result;
                        });
                return McpSchema.CallToolResult.builder()
                    .addTextContent(Jsons.write(value))
                    .isError(false)
                    .build();
              } catch (ApiException e) {
                return McpSchema.CallToolResult.builder()
                    .addTextContent(Jsons.write(Map.of("code", e.code, "message", e.getMessage())))
                    .isError(true)
                    .build();
              } catch (Exception e) {
                return McpSchema.CallToolResult.builder()
                    .addTextContent(
                        Jsons.write(Map.of("code", "TOOL_FAILED", "message", "工具执行失败，请核对参数与对象状态")))
                    .isError(true)
                    .build();
              }
            }));
  }

  private static String required(Map<String, Object> a, String key) {
    String value = Objects.toString(a.get(key), "");
    if (value.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "缺少参数：" + key);
    return value;
  }
}
