# 接口开发与联调说明

本文对应仓库当前实现，面向前端开发、测试和后续系统集成人员。请求与响应的字段定义以 [OpenAPI 契约](openapi.json) 和源码 DTO 为准；本文补充鉴权、业务约束、使用顺序及尚未接入的外部系统边界。接口统一前缀为 `/api/v1`，MCP 使用独立路径 `/mcp`。

## 1. 接口文档与访问地址

默认完整 Compose 的入口如下；端口改动后以 `.env` 为准。

| 内容 | 本地地址/文件 | 访问条件 |
| --- | --- | --- |
| 系统统一入口 | `http://localhost:19380` | 浏览器登录 |
| API（经 Web 与网关） | `http://localhost:19380/api/v1` | Bearer access token |
| API（直接网关） | `http://localhost:19382/api/v1` | 同上 |
| API（直接后端，开发排查） | `http://localhost:19381/api/v1` | 同上 |
| 在线 OpenAPI JSON | `http://localhost:19380/api/v1/openapi` | 同上；直接网关/后端端口也可使用 |
| 离线 OpenAPI JSON | [`docs/openapi.json`](openapi.json) | 无需启动，可导入 Postman、Apifox 等工具 |
| MCP Streamable HTTP | `http://localhost:19380/mcp` | JWT + MCP 协议握手 |
| 后端健康检查 | `http://localhost:19381/actuator/health` | 无需登录；不是业务访问凭证 |
| OIDC 元数据 | `http://localhost:19383/realms/assets/.well-known/openid-configuration` | 本地开发身份服务 |

当前对外开放的是受保护的 OpenAPI JSON。后端安全规则只允许健康检查、受保护的 `/api/**` 和 `/mcp`；默认 `/swagger-ui/index.html` 不在放行范围内，网关也不转发该路径，因此不能把它当作已开放的文档页面。可直接导入离线 JSON，或使用令牌取得在线规范。后续如增加 Swagger UI，需同时配置其资源路由和鉴权策略，不能为展示文档放开业务 API。

OpenAPI 由 Springdoc 从当前控制器生成，声明 `bearerAuth`（HTTP Bearer JWT）安全方案和全局安全要求。导入支持 OpenAPI 的工具后，可在 Bearer 鉴权项填入 access token；如工具未自动识别，在全局认证中设置 `Authorization: Bearer <access_token>`。不要把真实令牌保存到仓库、接口示例或公开分享的环境变量文件中。

## 2. 身份认证与项目授权

### 2.1 浏览器与本地调试

浏览器使用 OIDC 授权码与 PKCE（S256），客户端 `assets-web`，realm `assets`。本地开发管理员为 `admin`，密码 `123456`；这是本地演示账号，生产身份和凭据应由现场 OIDC 服务管理。

API 接收 **access token**，校验签名、有效期、`iss`、`aud` 包含 `assets-api`，以及非空 `sub`。ID token 不能代替 access token。

本地 realm 为联调和自动测试开启了 password grant。以下 Python 示例不把令牌写入文件，也不输出令牌；仅用于本地开发。生产客户端应使用现场允许的授权流程，不依赖该调试方式。

```python
import json
from urllib.parse import urlencode
from urllib.request import Request, urlopen

form = urlencode({
    "client_id": "assets-web",
    "grant_type": "password",
    "username": "admin",
    "password": "123456",
    "scope": "openid profile email",
}).encode()
with urlopen("http://localhost:19383/realms/assets/protocol/openid-connect/token", form) as response:
    token = json.load(response)["access_token"]

headers = {"Authorization": "Bearer " + token}
request = Request("http://localhost:19380/api/v1/auth/me", headers=headers)
with urlopen(request) as response:
    print(json.load(response))

# 如需更新离线契约，执行下列请求后再核对差异。
request = Request("http://localhost:19380/api/v1/openapi", headers=headers)
with urlopen(request) as response, open("docs/openapi.json", "wb") as destination:
    destination.write(response.read())
```

调用业务接口使用 `Authorization: Bearer <access_token>`；JSON 写请求另带 `Content-Type: application/json`。可选 `X-Request-ID` 接受 1～64 位字母、数字和连字符；服务端始终在响应头返回 `X-Request-ID`，便于日志关联。

### 2.2 项目范围与操作权限

`GET /auth/me` 返回 `subject`、`roles`、`projects`、`projectPermissions` 等字段。界面应按 **当前项目** 的 `projectPermissions` 判断操作，不能把所有项目权限合并后用于某个项目。

资产等接口明确传递 `projectId`；任务和运行按 ID 查询时，服务端先读取其所属项目，再检查该项目授权。数据库 `access_grants` 是平台授权来源，OIDC 角色名为 `admin` 也不会绕过项目范围。默认管理员仅获授本地 `demo` 与 `sandbox`，其他项目需要另行授权。

| 权限 | 主要操作 |
| --- | --- |
| `asset.read` / `asset.write` / `asset.execute` | 查询与导出 / 维护、导入、入库 / 发现、同步、刷新观测 |
| `task.read` / `task.write` / `task.execute` | 查询任务和运行 / 配置维护与校验 / 执行、回退及受控核查 |
| `deployment.confirm` | 确认冻结生产计划及相关生产操作 |
| `alarm.read` / `alarm.write` | 查询 / 规则维护和事件处置 |
| `observation.write` / `notification.read` | 提交观测 / 读取本人通知与标记已读 |
| `connection.read` / `connection.write` | 查询 / 维护连接及测试 |
| `access.manage` / `audit.read` | 当前项目授权管理 / 审计查询 |
| `dashboard.read` | 总览；还需资产、任务、告警读取权限 |

定时任务和采集计划使用独立 `serviceSubject`，执行前重新核查授权。人员身份与服务身份不能互换。MCP 沿用同一授权，不扩大权限。

回退和 UNKNOWN 结果核查同时要求 `task.execute`、`deployment.confirm`；采集计划维护和执行要求 `asset.read`、`asset.execute`，并核对独立服务主体的相同项目授权。

## 3. API 分组索引

下表路径均相对于 `/api/v1`。完整请求字段、响应类型和方法清单见 OpenAPI；文件下载响应不是 JSON。

| 分组 | 方法与路径 | 用途 |
| --- | --- | --- |
| 身份与授权 | `GET /auth/me`；`GET /access-grants`；`PUT /access-grants/{principalId}` | 当前身份、项目权限及受控授权维护 |
| 总览与审计 | `GET /dashboard`；`GET /audit` | 项目统计、近期记录、分页审计 |
| 资产台账 | `GET/POST /assets`；`GET/PUT/DELETE /assets/{id}` | 查询、登记、更新和草稿删除 |
| 入库与历史 | `POST /assets/{id}/admit`、`/deactivate`；`GET /assets/{id}/versions` | 当前版本入库、停用和版本证据 |
| 发现与同步 | `POST /assets/discover`；`POST /assets/sync/preview`、`/confirm` | 模拟发现、同步差异预览与确认 |
| 数据交换 | `POST /assets/import/preview`、`/confirm`；`GET /assets/export` | CSV/JSON/XLSX 预览导入和按筛选范围导出 |
| 观测与资产关系 | `GET/POST /assets/{id}/monitor`；`GET/POST /relationships`；`DELETE /relationships/{id}`；`GET /model/catalog` | 观测、关系及当前模型映射 |
| 任务配置 | `GET/POST /tasks`；`GET/PUT/DELETE /tasks/{id}` | 集群、VM、环境、应用部署和分发共用配置入口 |
| 任务操作 | `POST /tasks/{id}/validate`、`/confirm`、`/execute` | 校验、版本冻结确认、异步执行受理 |
| 运行记录 | `GET /runs`、`/runs/{id}`、`/runs/{id}/logs`；`POST /runs/{id}/rollback`、`/reconcile` | 进度、日志、逐目标结果、回退和未知结果核查 |
| 周期采集 | `GET/POST /collection-schedules`；`PUT /collection-schedules/{id}`；`POST /collection-schedules/{id}/execute`；`GET /collection-runs`；`POST /collection-runs/{id}/resolve` | 范围明确的发现/同步计划与持久执行 |
| 连接配置 | `GET /connections`；`PUT /connections/{id}`；`POST /connections/{id}/test` | 现有连接配置与未接入/模拟回执 |
| 告警规则 | `GET/POST /alarm-rules`；`PUT/DELETE /alarm-rules/{id}`；`POST /alarm-rules/{id}/test` | 规则及持久化通知测试回执 |
| 观测与事件 | `POST /observations`；`GET /alarms`、`/alarms/{id}`；`POST /alarms/{id}/handle` | 样本接收、事件去重和处置记录 |
| 通知 | `GET /notifications`；`POST /notifications/{id}/read` | 当前接收者的通知和已读记录 |

任务 `kind/type` 按以下组合选择，`config` 必填项由相应类型校验，不可把不同类型配置互换：

| kind | type |
| --- | --- |
| `CLUSTER` | `PVE`、`K8S` |
| `VM` | `GENERAL`、`COMPUTE`、`STORAGE` |
| `ENVIRONMENT` | `CONTAINER_PLATFORM`、`MICROSERVICE_FRAMEWORK`、`CLIENT_ENVIRONMENT`、`HOTUPDATE_SERVER` |
| `DEPLOYMENT` | `APPLICATION` |
| `DISTRIBUTION` | `FLATPAK`、`CLIENT_PUSH`、`FLUTTER_HOTUPDATE` |

## 4. 常用业务调用顺序

### 4.1 登记、维护与入库

`POST /assets` 返回创建后的完整记录，HTTP 状态为 `201`。例如：

```json
{
  "projectId": "demo",
  "name": "演示应用资产",
  "type": "SOFTWARE",
  "mode": "SIMULATED",
  "category": "教学应用",
  "technical": {"softwareVersion": "1.0.0"}
}
```

保存响应中的 `id` 和 `version`，后续更新 `PUT /assets/{id}` 携带 `projectId`、当前 `version` 及所需维护字段。入库请求为 `POST /assets/{id}/admit`，正文 `{"projectId":"demo","version":0}`，其中版本必须取刚读取的值，不能固定为 0。

类型仅为 `SERVER`、`CLIENT`、`NETWORK`、`SOFTWARE`；模式仅 `REAL`、`SIMULATED`，模拟仅允许 `demo`。同名资产可以并存；外部资源以连接器和 `externalId` 映射，IP 在网络范围内检查，序列号和 MAC 标准化后检查重复。分类变更保留资产 ID。入库只确认台账状态，不能解释为资源创建或在线。

### 4.2 导入与同步

导入预览使用 `multipart/form-data`：字段 `projectId`，文件字段 `file`。支持 UTF-8 CSV、JSON、XLSX，文件最大 20 MiB，预览最多 5000 行。解析格式与列说明见 [使用手册](user-guide.md)。确认正文为 `{"projectId":"demo","previewId":"预览返回的ID"}`。响应分别报告 `applied` 和 `rejected`，调用方不能把部分有效当作全部导入成功。

同步先 `POST /assets/sync/preview`，再人工核对并 `POST /assets/sync/confirm`。模拟已有 fixture 资产可传：

```json
{"projectId":"demo","mode":"SIMULATED","connectorId":"fixture","assetIds":["已有模拟资产ID"]}
```

预览保存当前版本并在一小时后过期；期间人工修改产生版本冲突时，应重新获取预览。来源未知值不会覆盖已核实字段，人工保护字段继续保留，不完整采集也不删除未返回资产。重复确认已完成的同一预览返回原处理结果。

### 4.3 配置、确认、执行与日志

1. `POST /tasks` 保存配置，取得任务 ID 和版本；保存不触发外部变更。
2. `POST /tasks/{id}/validate`，正文 `{"version":当前版本}`，检查 `valid` 和 `errors`。
3. 生产操作先 `POST /tasks/{id}/confirm`，正文同上。确认冻结任务、目标和依赖版本；任一相关内容变化均需重新确认。
4. `POST /tasks/{id}/execute`，正文如下。当前实现 HTTP 返回 `200` 和 **Run 对象**；运行 ID 字段为 `id`，不是单独的 `runId` 字段，也不表示任务同步完成。
5. 轮询 `GET /runs/{id}`，并读取 `/runs/{id}/logs`；检查总体状态及每个 `targets[]` 的状态和结果。

```json
{"version":3,"idempotencyKey":"local-demo-20261022-0001"}
```

`idempotencyKey` 是 **JSON 正文字段**，长度 8～200，只接受字母、数字及 `:_.-`。当前接口不从 `Idempotency-Key` 请求头提取执行幂等键；同样，版本使用正文/查询中的 `version`，不读取 `If-Match` 代替版本。

同项目下相同幂等键及相同任务/版本/动作返回原运行；键被不同请求复用返回冲突。网络超时后应使用原键获取原回执。每个任务同时只能有一个活动或 UNKNOWN 运行，不能换新键绕过。

运行状态包括 `QUEUED`、`RUNNING`、`SUCCEEDED`、`PARTIAL_FAILED`、`FAILED`、`UNKNOWN`。`UNKNOWN` 表示可能存在外部副作用而结果不明，必须核查，不能自动重试创建资源；核查接口要求证据引用和说明。真实资源未接入时执行返回 `503 NOT_CONFIGURED`，不产生虚假的成功。当前模拟运行仅写入隔离的模拟结果，不更新真实部署版本。

### 4.4 观测、告警与通知

`POST /observations` 提交 `projectId`、`assetId`、`mode`、`externalEventId`、`observedAt` 和 `metrics`。时间使用含时区的 ISO-8601，建议 UTC；指标示例 `{"CPU":95,"ONLINE":1}`。`externalEventId` 在项目范围内去重。响应 `DUPLICATE`、`STALE`、`OUT_OF_ORDER` 分别表示重复、过期、乱序样本，不能当作新鲜健康证据。

`POST /alarms/{id}/handle` 携带项目、当前版本、`status`（`ACKNOWLEDGED`/`RESOLVED`）和非空 `note`。处置完成不自动修改观测恢复事实。`IN_APP` 通知可用；EMAIL/WEBHOOK 当前保留 `NOT_CONFIGURED` 回执。通知已读也不会消除资产故障。

## 5. 查询、响应与错误处理

资产查询采用服务端分页：`GET /assets?projectId=demo&page=1&pageSize=20`，`page` 从 1 开始，`pageSize` 为 1～200。返回形如：

```json
{"items":[],"total":0,"page":1,"pageSize":20}
```

支持筛选 `type`、`category`、`admission`、`mode`、`resourceStatus`、`environment`、`dept`、`lifecycle`、`owner`、`search`、`source`。排序字段仅 `name`、`type`、`category`、`admission`、`dept`、`owner`、`updatedAt`、`createdAt`，方向 `asc`/`desc`，默认更新时间倒序。资产导出使用同一筛选范围，重新检查项目权限，不限于当前分页。

审计也返回 `PageResult`，默认每页 20，最大 100。任务、运行、规则、通知、连接和采集计划等当前返回数组，不能假定所有列表接口均分页；以后如调整此契约，应同时更新调用方和 OpenAPI。

大多数领域异常响应包括 `code`、`message`、`traceId`；安全层的 401/403 JSON 可能不含 `traceId`，可取响应头 `X-Request-ID`。不要依赖 `message` 做程序分支，应按 HTTP 状态和 `code` 处理。

| HTTP 状态 | 常见 code | 调用方处理 |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR`、`VALIDATION`、`INVALID_PAGINATION` 等 | 修正请求字段/类型，不自动重试 |
| `401` | `UNAUTHENTICATED` | 重新登录或取得有效 access token |
| `403` | `FORBIDDEN`、`SERVICE_NOT_AUTHORIZED` | 检查当前项目和主体权限 |
| `404` | `NOT_FOUND` | 刷新对象列表，核对 ID |
| `409` | `VERSION_CONFLICT`、`DATA_CONFLICT`、`ACTIVE_RUN`、`CONFIRMATION_REQUIRED` 等 | 刷新版本/状态，必要时重新预览或确认 |
| `413` | `IMPORT_TOO_LARGE` | 拆分导入文件 |
| `422` | `SIMULATION_ISOLATION` 等 | 修正模式/范围或业务条件 |
| `503` | `NOT_CONFIGURED`、`ADAPTER_NOT_CONFIGURED` | 真实适配尚未接入；不能解释为已执行 |
| `500` | `INTERNAL_ERROR` | 保存追踪编号供服务日志排查 |

跨源本地调试仅允许配置的 `CORS_ORIGINS`；默认包含 19380、19385、5173。正式运行应使用受控域名、TLS 和现场身份配置。

## 6. MCP 工具入口

`/mcp` 采用官方 MCP Java SDK 的 Servlet Streamable HTTP。客户端需提供相同 Bearer JWT，支持 `Accept: application/json, text/event-stream`，先 `initialize`、`notifications/initialized`，再使用 `tools/list` / `tools/call`；按响应处理 `Mcp-Session-Id` 及协商的 `MCP-Protocol-Version`。可参照仓库 [`tests/mcp.py`](../tests/mcp.py) 的实际握手。

工具白名单共 9 项：`assets_search`、`assets_detail`、`assets_create`、`assets_sync_preview`、`assets_sync_apply`、`tasks_list`、`tasks_execute`、`runs_status`、`runs_logs`。每个工具携带 `projectId`，执行工具另需当前 `version` 和 `idempotencyKey`；参数以 `tools/list` 返回 schema 为准。没有任意脚本、任意 URL、生产自动确认或跳过权限工具。

MCP 协议请求成功不等于业务成功；检查结果的 `isError`，文本内容包含同领域错误码。工具调用和 HTTP 调用进入同一业务服务并写入审计。客户端 token 获取及刷新由接入客户端负责，当前未增加独立的 MCP OAuth 自动发现/注册服务。

## 7. 契约更新与外部接口预留

修改控制器或 DTO 后，启动服务、用授权请求重新导出 `docs/openapi.json`，核对破坏性变更，再执行：

```sh
npm --prefix frontend run generate:api
npm --prefix frontend run build
```

提交 OpenAPI 和生成的 `frontend/src/generated/api.ts`；不要直接手工改生成类型。HTTP 与 MCP 联调验证入口分别为 `tests/integration.py`、`tests/mcp.py`，运行方法见本地执行手册与测试报告。

真实 PVE/K8S、流水线、制品、监控、通知及学校身份接入的配置位置、证据要求和后续边界见 [外部系统接入预留](integrations.md)。当前在线文档描述的是已有 API，不把“预留”写成已经完成外部调用。
