# AIDevOps 资产台账使用手册

适用范围：本仓库的独立资产台账，包括七个业务页面，以及“连接配置与审计”支撑页。数据登记、配置维护、权限、执行记录、导入导出和站内通知使用真实的本地持久化；外部基础设施操作目前只有明确标记的模拟执行。功能覆盖和真实接入缺口见 [需求追踪](requirements-traceability.md)。

## 1. 启动、地址与账号

安装并启动支持 Compose 的 Docker，然后在工程根目录执行：

```sh
cp .env.example .env
./scripts/start.sh
```

首次启动需要下载依赖、构建镜像并导入本地身份 realm。脚本发出服务启动命令不代表每项服务都已就绪；通过页面登录和服务健康结果核对实际状态。

| 服务 | 默认本地地址 | 用途 |
| --- | --- | --- |
| Web | http://localhost:19380 | 日常使用入口 |
| API | http://localhost:19381 | 受保护业务 API；健康检查为 `/actuator/health` |
| Gateway | http://localhost:19382 | 统一 API/MCP 转发入口 |
| Keycloak | http://localhost:19383 | `assets` realm 的开发身份服务 |
| PostgreSQL | `localhost:19384` | 数据库 `assets`；Keycloak 使用独立数据库 `keycloak` |

端口可在 `.env` 调整；调整身份或前端公开地址时，还需同步 OIDC issuer、客户端重定向和前端配置。仅修改一个端口不构成完整身份配置变更。

本地 realm 的开发账号如下。平台通过 Keycloak 登录，不单独维护这些密码；这些账号不代表学校真实账号或正式岗位授权。

| 账号 | 开发密码 | 当前初始平台权限 |
| --- | --- | --- |
| `admin` | `Admin-local-2026!` | `demo` 和 `sandbox` 项目全部平台权限，包含连接与授权管理 |
| `ops` | `Demo-local-2026!` | `demo` 项目资产/任务维护、执行、生产确认、告警规则与观测维护，以及读取权限 |
| `developer` | `Demo-local-2026!` | `demo` 项目读取权限 |
| `qa` | `Demo-local-2026!` | `demo` 项目读取权限 |
| `project` | `Demo-local-2026!` | `demo` 项目读取权限 |

右上角选择当前项目。`demo` 用于模拟闭环；只有 `admin` 初始拥有 `sandbox`，后者用于查看真实资源未接入状态。后续实际权限以项目授权配置为准，同一岗位名称不会自动取得其他项目权限。Keycloak 管理控制台的引导管理员和上表的业务 realm 账号是不同身份。

## 2. 七个业务页面

### 2.1 资产总览 `/overview`

查看当前项目资产分类、入库数量、计算/环境/交付任务、运行状态和最近告警。点击页面内入口进入对应管理页。这里的统计来自本项目数据库，模拟任务和资产带有模式标记；数量增长不证明真实资源已经创建。

### 2.2 资产管理 `/assets`

- **查询**：列表使用数据库筛选和分页；API 支持 source 来源过滤，以及 `sortBy`（name/type/category/admission/dept/owner/updatedAt/createdAt）与 `sortOrder=asc/desc`，默认更新时间降序并以 ID 稳定排序。
- **手动新增/维护**：选择服务器 `SERVER`、客户端 `CLIENT`、网络设备 `NETWORK` 或软件资产 `SOFTWARE`，填写分类、部门、责任人、环境、用途、位置、技术信息、运维信息与生命周期。填写管理 IP 时必须填写网络范围 `networkScope`。
- **外部身份**：外部资源有稳定 ID 时，同时填写连接器 `connectorId` 和外部 ID `externalId`。名称允许相同，外部身份不能靠改名改变。已有资产的类型、执行模式及外部身份不通过普通编辑转换。
- **网络扫描**：`SIMULATED` 在 `demo` 中创建四类 fixture 候选，不访问填写的真实网段。`REAL` 当前返回适配器未配置。需要自动执行时，在支撑页配置独立的 DISCOVERY 采集计划。
- **资产入库**：核对候选的分类、归属和责任后入库；批量入口对勾选项逐项办理。入库是台账确认，不等于设备在线或集群就绪。
- **停用**：将已入库资产退出可执行目标范围，保留记录和历史；不会删除外部云资源。已停用资产不能直接重新入库，需另行明确恢复流程。
- **详情/版本/关系**：查看完整字段、来源、采集时间、人工保护字段和历次资产快照；“资产关系”支持 MEMBER_OF、HOSTED_ON、DEPLOYED_TO、USES、DEPENDS_ON。关系双方须为本项目同模式的已入库资产，不能指向自身；移除关系需当前版本，不删除双方资产。编辑资产提交当前 `version`；数据已变更时刷新后重试。
- **监控**：读取同项目、同资产、同模式最新观测。无观测、无指标或超过 300 秒显示 `UNKNOWN`；新鲜观测显示 `OBSERVED`、`ALERT` 或 `OFFLINE`，来源始终保留。模拟 `READY` 是模拟资源条件，不是真实健康。
- **导入/导出**：支持 CSV、JSON、XLSX。先预览逐行结果，再确认导入有效行；拒绝行单独计数。导出遵循当前项目和筛选条件，不限于当前显示页。
- **同步**：先生成差异预览，再确认；人工保护字段和未提供的未知值不会被静默覆盖。不完整来源不会删除未返回资产。预览一小时过期，确认前资产版本变化会拒绝旧预览。支撑页的 SYNC 采集计划可周期生成待确认预览，仍由有权限人员核对并另行确认应用。

### 2.3 计算资源 `/compute`

此页包含“集群管理”和“虚拟机管理”两种任务。

创建集群时选择 PVE 或 K8S、执行模式及已入库目标服务器，填写平台版本、节点角色、管理网络、存储和凭据引用。K8S 还需服务网络。保存只保存配置；依次进行配置校验、需要时的配置确认、执行，并从历史查看运行及逐目标日志。

创建虚拟机时选择本项目同模式的**已成功集群**及其物理宿主，填写模板、CPU、内存、磁盘、存储池、网络桥、IP 分配和初始化凭据引用。宿主须属于所选集群。模拟模板目录包含 `ubuntu-24.04`、`debian-12`、`rocky-9`、`openEuler-24.03`。容量会扣除已登记保留量、已有模拟 VM 及排队/运行/未知运行的申请；不足时不能执行。

真实集群和 PVE 创建适配器尚未接入。模拟运行不会创建真实 VM，也不会把模拟结果写入真实资产清单。

### 2.4 环境部署 `/environment`

选择类型后填写对应配置。所有类型都要指定已入库、可用、同模式的目标；需要的依赖任务必须在同项目同模式下成功。

| 类型 | 服务端校验的关键配置 |
| --- | --- |
| 容器平台 `CONTAINER_PLATFORM` | `platform`（Kubernetes/Docker Swarm）、`version`、`nodeRoles`、`network`、`storage` |
| 微服务框架 `MICROSERVICE_FRAMEWORK` | `version`、`namespace`、`storage`、`domain` |
| 客户端环境 `CLIENT_ENVIRONMENT` | Flatpak `version`、`repository`、`credentialRef`、`strategy` |
| 热更新服务器 `HOTUPDATE_SERVER` | `version`、`port`、`directory`、`credentialRef`、`strategy` |

保存、校验、执行和查看日志分别办理。页面参数与回执不代表 K8S、Entando、Flatpak 或热更新服务已经真实安装；真实兼容性与就绪验证需现场接入。

### 2.5 应用部署 `/deployment`

创建带版本的部署计划，填写制品权威引用、`sha256:` 后接 64 位十六进制的摘要、**已成功环境任务的 ID**、目标、命名空间、发布策略、健康检查、超时和失败策略。目标须属于所选环境。灰度策略需有效灰度比例；自动回退策略需同项目同模式成功部署作为回退基线。

配置确认会冻结当前任务配置、目标版本及制品引用，执行仍是另一个动作。勾选“生产操作”后必须先由拥有 `deployment.confirm` 权限的主体确认当前版本，再执行；任务或目标版本改变后旧确认失效。同一人可以同时具有确认和执行权限，当前没有独立多级审批引擎。

运行详情显示受理、排队、执行、逐目标结果和日志。成功模拟部署可发起独立回退运行：存在前一版本时恢复前一模拟状态；首次部署回退表示模拟撤回。真实发布、流水线回执和实际回滚尚未接入。

### 2.6 应用分发 `/distribution`

| 类型 | 操作配置 |
| --- | --- |
| Flatpak 应用打包 `FLATPAK` | 应用 ID、制品引用、版本、架构、运行时、目标仓库 |
| 客户端应用推送 `CLIENT_PUSH` | 应用 ID、版本、Flatpak 仓库、策略、失败阈值及目标客户端 |
| Flutter 热更新及动态加载 `FLUTTER_HOTUPDATE` | 应用、基础版本、更新包引用、补丁版本、签名凭据引用、动态模块、入口、加载策略和验证要求 |

保存配置后校验并执行，按目标查看回执。当前模拟器不会生成真实 Flatpak 包、访问真实仓库或向客户端加载 Flutter 模块；发布受理、仓库接收和客户端更新完成不能互相替代。

### 2.7 告警管理 `/alarms`

在“告警规则”新增规则，选择硬件、部署或分发来源，配置级别、指标、比较方式、阈值、持续时间、目标范围和通知渠道，再明确启用。硬件规则由收到的新鲜观测判定；部署/分发失败由 worker 观察已有运行结果。

在 `demo` 中可用“提交模拟观测”向已入库模拟资产提交 CPU、MEMORY、DISK 或 ONLINE 样本。新鲜样本可命中规则，过期/乱序样本保留但不当作当前健康。连续条件需要后续样本累计持续时长；演示单次触发可将持续时间设为 0。

“处理”记录处理人和说明，`ACKNOWLEDGED` 表示处理中，`RESOLVED` 表示已处理。处理完成不会伪造设备恢复；`observedRecovery` 仅随实际后续观测判定变化。顶部通知中心只显示当前用户的站内通知，已读不会消除故障。`IN_APP` 可持久化；EMAIL/WEBHOOK 当前返回 `NOT_CONFIGURED`，不宣称外部已发送或已送达。

## 3. 推荐的模拟闭环

以下步骤只在 `demo` 项目执行。首次数据为空属于正常状态。

1. 使用 `admin` 或 `ops` 登录，资产管理 → 网络扫描 → 选择 `SIMULATED`。核对生成的四类候选，至少将模拟服务器和模拟客户端入库。
2. 计算资源新建 `SIMULATED` PVE 集群，选择模拟服务器；填写演示平台版本、节点角色、管理网络、存储方案和 `demo:pve` 等凭据引用。校验后执行，观察运行从 QUEUED/RUNNING 到最终结果。
3. 使用已成功的集群新建模拟 VM，指定同一物理机和 `ubuntu-24.04` 模板，例如 8 CPU、32 GB 内存、120 GB 磁盘；填写存储池、网络桥、IP 分配与凭据引用。执行并查看宿主与资源申请记录。
4. 环境部署创建模拟容器平台任务，选择同一服务器，填写全部必填配置，执行成功后记录环境任务 ID。
5. 应用部署创建模拟计划：制品引用可用 `artifact://demo/app/1.0.0`，摘要填写符合格式的演示值；环境填写上一步 ID，目标仍为该服务器。填写其余必填项。若勾选生产，先确认当前配置版本，再执行。演示摘要仅用于流程校验，不是对真实制品内容的核验。
6. 应用分发创建模拟 Flatpak、客户端推送或 Flutter 任务，填写对应类型参数并执行，查看逐目标回执及日志。
7. 告警管理新建已启用的硬件规则：CPU `GT 80`、持续 0 秒、目标为模拟服务器、渠道 `IN_APP`。提交 CPU=95 的模拟观测，查看告警和个人通知；再提交 CPU=20，核对观测恢复标记。查看资产监控保留的模式、指标和时间。
8. 回到资产管理，修改分类后查看版本；导出一份 CSV/JSON/XLSX。导入前更改外部身份/IP，或在新的授权项目使用真实手动样例，避免重复身份被拒绝。同步则先预览并核对保护字段，再确认。到支撑页查看审计。

测试和演示应记录具体输入、任务/运行 ID、来源模式及实际结果；手册中的操作步骤本身不是通过证据。

## 4. 连接配置、调度和审计

左侧底部“连接配置与审计”对应 `/settings`。查看连接器类型、模式、端点、凭据引用、启用状态和测试回执。修改连接需要管理员的 `connection.write` 权限；普通 `ops` 初始只有连接读取权限。当前 REAL 连接即使填入端点，仍需实现适配器和现场验证，测试返回 `NOT_CONFIGURED`。

部署等任务可配置 `schedule.enabled`、`intervalMinutes` 和 `serviceSubject`；默认关闭。它与下面的资产发现/同步计划分别管理。开发服务身份为 `asset-scheduler-demo`，与人员账号分别核权；错过的历史调度不批量补发，活动或 UNKNOWN 运行不能靠重复调度绕过。

### 采集计划：自动发现与同步

在“连接配置与审计 → 采集计划”新增 DISCOVERY（资源发现）或 SYNC（资产同步）：

1. 选择模式，填写计划名、间隔分钟、IANA 时区（默认 `Asia/Shanghai`）及独立服务主体。默认停用；保存即记录当前有权人员确认的对象范围，未另设多级审批流。
2. 模拟 DISCOVERY 只接受 `connectorId=fixture`、`networkScope=demo-lan`。模拟 SYNC 使用 `fixture`，并明确选择 1～100 个已入库同模式资产，不能默认同步全池。配置只允许 connectorId/networkScope/assetIds，不接受任意 rows 或明文凭据。
3. 开发主体可填 `asset-scheduler-demo`，服务端会在保存、执行时核对其当前 `asset.read`、`asset.execute` 授权，即使计划停用也要有合法服务身份和范围。
4. 保存后可“执行一次”，或启用后等待周期执行。到期和领取使用 PostgreSQL 锁及租约，停机积压不会集中补跑。
5. DISCOVERY 成功产生模拟候选。SYNC 结束为 **PREVIEW_READY**，在“采集执行记录”打开结果、检查差异和保护字段，再点“确认应用同步预览”；自动计划本身不改写已有主账。预览一小时过期或目标版本变化需重新采集。
6. REAL 可保存已授权范围内的停用计划；当前不能启用真实周期采集。手动执行会保存 FAILED/NOT_CONFIGURED 回执并返回未配置错误，没有扫描或同步真实资产。
7. 租约过期记录为 UNKNOWN，阻止自动重放；需通过核查接口提供证据和至少 10 字说明，关闭为 CLOSED_UNKNOWN。关闭只归档不确定结果，不把其改成成功，也不重放原运行。

采集计划接口为 `/api/v1/collection-schedules`、`/collection-schedules/{id}/execute`、`/collection-runs` 和 `/collection-runs/{id}/resolve`。真实协议、服务身份和现场范围仍须在接入时独立核对。

审计页按当前项目显示主体、动作、对象、时间、详情和追踪 ID。运行日志位于任务历史内。授权和日志留存周期没有在本轮新增统一合同承诺。

## 5. 导入模板与限制

CSV 使用 UTF-8；XLSX 读取第一个工作表的第一行为表头；JSON 可为资产对象数组，或 `{"assets":[...]}`。CSV/XLSX 表头大小写应与下表完全相同，不能重复或包含未支持列。单文件上限 20 MiB，单次预览最多 5000 行，这些是本产品当前可调整的处理边界，不是 SRS 的总资产数量上限。

| 字段 | 说明 |
| --- | --- |
| `name`、`type` | 必填；类型仅 `SERVER`、`CLIENT`、`NETWORK`、`SOFTWARE` |
| `category`、`owner`、`environment`、`dept`、`location`、`purpose` | 分类、责任人、环境、部门、位置和用途 |
| `mode` | `REAL` 或 `SIMULATED`，省略为 REAL；SIMULATED 只允许 demo |
| `connectorId`、`externalId` | 有外部 ID 时必须同时提供连接器；同模式外部身份不重复 |
| `networkScope`、`ip` | 有 IP 时必须提供网络范围；同范围同模式的有效资产 IP 不重复 |
| `technical` | 技术配置 JSON 对象 |
| `operation` | 运维信息 JSON 对象，例如 `{"owner":"运维组","collection":"人工维护"}` |
| `lifecycle` | 生命周期 JSON 对象，例如 `{"enabledDate":"2026-10-22"}` |
| `manualFields` | JSON 字段名数组，例如 `["name","owner","technical.os"]`；表示同步保护字段，保护 IP 会连带网络范围 |

CSV 示例：

```csv
name,type,category,owner,mode,networkScope,ip
示例手工服务器,SERVER,计算服务器,研发运维组,REAL,example-lan,192.0.2.40
```

JSON 示例：

```json
{
  "assets": [
    {
      "name": "示例手工服务器",
      "type": "SERVER",
      "mode": "REAL",
      "networkScope": "example-lan",
      "ip": "192.0.2.40",
      "technical": {"cpuCores": 32, "memoryGb": 128, "diskGb": 1000},
      "operation": {"collection": "人工维护"},
      "lifecycle": {"enabledDate": "2026-10-22"},
      "manualFields": ["name", "technical.cpuCores"]
    }
  ]
}
```

以上手工登记示例的资源状态为 UNKNOWN，不因填写容量而成为真实可用资源。XLSX 不接受公式单元格；CSV 导出会中和可能被表格软件识别为公式的文本。预览失败不写主账；确认只应用有效行，返回应用与拒绝数量；同一确认重试复用结果。

## 6. 常见状态与错误

| 状态/错误 | 含义与处理 |
| --- | --- |
| `DRAFT` / `CANDIDATE` / `ADMITTED` / `INACTIVE` | 台账草稿/候选/入库/停用；与监控健康、任务执行状态分开理解 |
| `UNKNOWN` 监控 | 缺失、过期或无效观测；核对来源与采集时间，不等同健康 |
| `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` | 已受理排队/执行中/完成/失败；同时查看 mode、逐目标结果和日志 |
| `PREVIEW_READY` 采集运行 | 同步已生成差异，尚未应用；核对后另行确认 |
| `CLOSED_UNKNOWN` / `SKIPPED` 采集运行 | 未知结果已按证据关闭 / 错过周期被跳过，均不表示采集成功 |
| `UNKNOWN` 运行 | 执行结果不能确定；禁止自动重放。需有权限人员提供证据引用、SUCCEEDED/FAILED 结论和至少 10 字说明后核查；核查不重新执行外部动作 |
| `401 UNAUTHENTICATED` | 登录失效或令牌不合法，重新登录 |
| `403 FORBIDDEN` | 当前项目或操作权限不足，核对授权而非反复提交 |
| `409 VERSION_CONFLICT` / `PREVIEW_EXPIRED` | 数据被修改或预览过期，刷新并重新预览/确认 |
| `DUPLICATE_EXTERNAL_ID` / `DUPLICATE_IP` | 稳定外部身份或同网络范围 IP 已登记，核对原资产，不能靠改名规避 |
| `CONFIRMATION_REQUIRED` / `CONFIRMATION_STALE` | 生产计划未确认或确认后内容/目标变化，核对当前版本并重新确认 |
| `ACTIVE_RUN` | 已有活动或未知运行，先查看和处理该运行 |
| `ASSET_NOT_ADMITTED` / `ASSET_NOT_READY` | 目标未入库、已停用或资源就绪条件未知 |
| `MODE_MISMATCH` / `SIMULATION_ISOLATION` | 模拟与真实目标混用，或在非 demo 项目请求模拟执行 |
| `CAPACITY_EXCEEDED` / `DEPENDENCY_NOT_READY` | 容量不足或依赖任务不满足，同项目同模式的依赖必须成功 |
| `503 ADAPTER_NOT_CONFIGURED` / `NOT_CONFIGURED` | 真实资源或执行适配器未接入，未执行真实操作；连接元数据保存不能改变这一事实 |

页面报错时保留追踪 ID、项目、对象和任务/运行 ID，便于从审计及服务日志定位。结果未知时不要用新幂等键反复执行。

## 7. 备份、恢复和生产接入

本地数据使用 Compose 的 `assets-data` 卷。`docker compose down` 停服务但保留卷；删除卷会丢失本地台账、运行与身份数据。

```sh
./scripts/backup.sh
```

备份脚本分别导出业务数据库及 Keycloak 数据库。恢复前先备份当前数据，核对目标环境和备份版本；恢复脚本要求显式设置确认变量：

```sh
CONFIRM_RESTORE=yes ./scripts/restore.sh backups/所选业务备份.dump
```

恢复会替换目标库对象。完成后还需实际核对登录、资产分类、外部映射、任务快照和关键关联，不能只看脚本退出状态。

生产接入应关闭开发数据初始化，替换本地身份配置及开发密码，接学校现有 Keycloak/OpenID，并逐项配置平台项目授权。资源与存储使用甲方实际提供和批准的范围；现场确认 PVE/K8S/监控/流水线/制品/通知接口版本、凭据引用及读写边界后实现真实适配器。正式兼容、性能、40TB 有效存储和现场验收按原 SRS 独立验证，本地模拟闭环不替代这些结果。
