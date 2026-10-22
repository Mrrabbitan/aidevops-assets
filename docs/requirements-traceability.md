# 资产台账需求追踪与交付边界

本文依据输入的《应用软件开发服务项目软件需求规格说明 SRS V2.8 GJB438C ysm 意见版》第 3.2.5 节、对应第 4 章表 4-5，以及当前代码编写。输入文件不在本代码仓库内，文件名“意见版”不代表所有意见已经批准；本文件不把原型中的产品补充功能改写为原合同义务。

本轮提供七个业务页面和一个支撑页面的独立工程：真实的本地数据管理、权限、执行记录与通知存储，以及隔离的资源执行模拟器。PVE、K8S、SNMP、流水线、制品仓库、Flatpak 和 Flutter 的真实现场执行没有接入。下表的“模拟”只表示存在明确标记的模拟执行代码，不表示真实系统验证通过。

## 状态和证据口径

| 标记 | 含义 |
| --- | --- |
| 本地 | 业务逻辑或数据持久化有实际代码实现，可在本地环境验证。 |
| 契约 | 已有参数、返回、授权或适配接口，尚不能据此证明外部动作已完成。 |
| 模拟 | 仅 `demo` 项目允许 `SIMULATED`；执行回执包含 `observed=false`，结果写入隔离的模拟表。 |
| 现场待联调 | 需要现场系统、有效接口资料、授权、网络、真实输入和回执；当前不能判为满足。 |
| 部分 | 当前实现只覆盖条款的一部分，缺项在边界列明示。 |

测试证据分类如下。这里列的是已有测试源码与应收集的证据，不是执行结果；是否通过以最终构建、测试和联调报告为准。

- **A：资产业务单元测试源码**：`AssetDomainTest`、`AssetServiceTest`，覆盖身份去重、网络范围、人工字段保护、版本冲突、模拟隔离及目标状态约束。
- **F：导入导出单元测试源码**：`AssetDocumentsTest`，覆盖 CSV 引号/换行、CSV/JSON/XLSX 往返、公式中和与错误表头。
- **M：监控单元测试源码**：`AssetMonitoringTest`，覆盖缺失、过期观测、模式来源和未观测恢复的告警状态。
- **T：任务单元测试源码**：`TaskRulesTest`、`TaskServiceTest`，覆盖分类配置、目标与模式、版本、生产确认、幂等和 UNKNOWN 阻断。
- **P：PostgreSQL 集成测试源码**：`TaskPostgresTest`，覆盖抢占、租约 fencing、授权撤销、冻结目标变化、幂等冲突、模拟结果隔离；需要可用数据库测试环境。
- **C：采集计划单元测试源码**：`CollectionScheduleRulesTest`，覆盖发现/同步范围、服务身份、时区、周期及真实未配置边界。
- **H：HTTP/页面验证**：`tests/integration.py` 包含本地 HTTP 闭环，`tests/mcp.py` 包含 MCP 协议和授权验证；执行项数、版本和结果以统一测试报告为准。新增代码还需随最终构建回归，不能用既有结果代替后续版本验证。
- **R：真实联调**：须保存原系统对象、授权配置、外部执行 ID、实际状态、版本/摘要以及失败和恢复证据；目前待现场实施。

相关测试源位于 `backend/src/test/java/cn/aidevops/assets/asset/` 、`backend/src/test/java/cn/aidevops/assets/task/` 和 `backend/src/test/java/cn/aidevops/assets/integration/`。前端 `frontend/src/lib/api.test.ts` 验证请求辅助逻辑，不构成全部业务页面的端到端验收。

## CAP-05 全部 21 条正式需求

按源文“需求编号”单元格核对，本节正式条款为 21 条：M01 五条、M02 四条、M03 六条、M04 四条、M05 两条；编号不连续，未将模块标题补算成需求。

以下 API 均以 `/api/v1` 为前缀。任务接口根据记录自身确定项目并核验权限；资产查询和变更显式携带 `projectId`。

| 原章节 / 正式需求编号 | 页面与入口 | API / 任务类型 | 当前实现类 | 当前交付与重要缺口 | 证据类别 |
| --- | --- | --- | --- | --- | --- |
| 3.2.5.1 / **SRS-CAP-05-M01-002** 网络扫描 | 资产管理 → 网络扫描；支撑页 → 采集计划 | `POST /assets/discover`；`/collection-schedules`、`/collection-runs` | `AssetService.discover`、`CollectionScheduleRules/Service/Worker` | 本地授权范围、独立服务身份、启停/版本、PG 租约调度及模拟自动发现四类候选。模拟范围限 fixture/demo-lan；REAL 不能启用，手动执行保存未配置失败回执。真实 SNMP/软件发现仍待联调，不宣称已扫描真实网段。 | A、C、H、R |
| 3.2.5.1 / **SRS-CAP-05-M01-003** 资产信息采集 | 资产详情、同步预览 | `POST /assets/sync/preview`、`POST /assets/sync/confirm` | `AssetService.normalize/mergeSource`、`AssetEntity` | 本地字段、来源时间、未知值保护及模拟来源合并；真实硬件/OS 采集未接入，没有独立的真实采集执行结果。 | A、H、R |
| 3.2.5.1 / **SRS-CAP-05-M01-004** 资产入库 | 资产分类列表、维护、入库 | `POST /assets`、`PUT /assets/{id}`、`POST /assets/{id}/admit` | `AssetService`、`AssetRepository`、`AssetVersionRepository` | 本地真实 CRUD、分类维护、入库、唯一身份及版本审计；外部身份按项目/模式/连接器/外部 ID 区分，IP 按网络范围；名称不唯一。多级审批并非本条规定。 | A、F、H |
| 3.2.5.1 / **SRS-CAP-05-M01-005** 集群初始化 | 计算资源 → 集群管理 | `/tasks`，`kind=CLUSTER`、`type=PVE/K8S`；validate/confirm/execute | `TaskRules`、`TaskService`、`TaskWorker`、`SimulationTaskAdapter` | 本地配置、目标关系、持久化执行和模拟回执；真实节点安装/加入待联调。Compose 是本平台独立安装入口，不等同 PVE/K8S 集群初始化成功。 | T、P、H、R |
| 3.2.5.1 / **SRS-CAP-05-M01-006** 虚拟机创建 | 计算资源 → 虚拟机管理 | `/tasks`，`kind=VM`；`GET /assets` | `TaskService.check`、`TaskRepository.checkCapacity`、`TaskWorker` | 本地指定成功集群和物理宿主、模板/容量检查、幂等及宿主关系，模拟 VM 状态隔离存储。真实 PVE 节点可用量、创建状态和超时查询/重试待适配。 | T、P、H、R |
| 3.2.5.2 / **SRS-CAP-05-M02-008** 容器平台部署 | 环境部署 → 容器平台部署 | `/tasks`，`kind=ENVIRONMENT`、`type=CONTAINER_PLATFORM` | `TaskRules`、`TaskService`、`TaskExecutionAdapter` | Kubernetes 或 Docker Swarm 的配置和模拟任务；真实依赖、节点就绪及工作负载调度验证待联调，不表示同时采用两种平台。 | T、P、H、R |
| 3.2.5.2 / **SRS-CAP-05-M02-009** 微服务框架安装 | 环境部署 → 微服务框架安装 | `/tasks`，`type=MICROSERVICE_FRAMEWORK` | `TaskRules`、`TaskService`、`SimulationTaskAdapter` | 版本/命名空间/存储/域名等参数和模拟任务；真实 K8S、Entando 兼容组合、安装/回退及独立引导包待确认。 | T、H、R |
| 3.2.5.2 / **SRS-CAP-05-M02-010** 客户端环境配置 | 环境部署 → 客户端环境配置 | `/tasks`，`type=CLIENT_ENVIRONMENT` | `TaskRules`、`TaskService`、`TaskWorker` | Flatpak 版本、仓库、凭据引用、策略及分目标模拟执行；真实 Linux 客户端运行时和仓库可用性尚未验证。 | T、H、R |
| 3.2.5.2 / **SRS-CAP-05-M02-011** 热更新服务器部署 | 环境部署 → 热更新服务器部署 | `/tasks`，`type=HOTUPDATE_SERVER` | `TaskRules`、`TaskService`、`TaskWorker` | 服务版本/端口/目录/凭据引用/策略配置和模拟回执；真实安装、服务健康、包接收和兼容读取待联调。 | T、H、R |
| 3.2.5.3 / **SRS-CAP-05-M03-013** 创建部署计划 | 应用部署 → 新建/维护 | `/tasks`，`kind=DEPLOYMENT`、`type=APPLICATION` | `TaskService`、`TaskRules`、`TaskRepository` | 本地带版本计划、制品引用与 SHA-256、已成功环境任务、目标与策略；制品权威内容不复制到资产域。真实受控制品可见性和批准来源校验仍待仓库适配。 | T、H、R |
| 3.2.5.3 / **SRS-CAP-05-M03-014** 服务端应用部署 | 应用部署 → 校验/配置确认/执行/回退 | `/tasks/{id}/validate/confirm/execute`、`/runs/{id}/rollback` | `TaskService`、`TaskWorker`、`TaskRepository`、`TaskExecutionAdapter` | 本地生产确认冻结、执行快照、分目标状态、模拟部署与独立回退运行；非生产可校验后执行。真实“已批准计划→流水线”对接、实际运行版本及回退待联调。 | T、P、H、R |
| 3.2.5.3 / **SRS-CAP-05-M03-015** Flatpak 应用打包 | 应用分发 → Flatpak 应用打包 | `/tasks`，`kind=DISTRIBUTION`、`type=FLATPAK` | `TaskRules`、`TaskService`、`SimulationTaskAdapter` | 应用/制品/版本/架构/运行时/仓库参数和模拟任务；未实际构建 Flatpak 包或上传仓库，包格式、依赖和校验值待真实验证。 | T、H、R |
| 3.2.5.3 / **SRS-CAP-05-M03-016** 客户端应用推送 | 应用分发 → 客户端应用推送 | `/tasks`，`type=CLIENT_PUSH`；`GET /runs/{id}` | `TaskRules`、`TaskWorker`、`TaskRepository` | 目标、版本、策略、阈值及分目标模拟回执；没有真实客户端安装回执，不将模拟成功解释为客户端已更新。 | T、P、H、R |
| 3.2.5.3 / **SRS-CAP-05-M03-017** Flutter 热更新发布 | 应用分发 → Flutter 热更新及动态加载 | `/tasks`，`type=FLUTTER_HOTUPDATE` | `TaskRules`、`TaskService`、`TaskExecutionAdapter` | 基础版本、更新包引用、补丁版本、签名引用与策略；当前发布与加载为一个任务配置中的模拟行为。真实包校验、服务上传及旧有效包保护待联调。 | T、H、R |
| 3.2.5.3 / **SRS-CAP-05-M03-018** 动态功能加载 | 同上，模块/入口/验证要求 | `/tasks`，`type=FLUTTER_HOTUPDATE`；`/runs/{id}` | `TaskRules`、`SimulationTaskAdapter`、`TaskRepository` | 模块、入口、加载策略和验证要求的契约及模拟回执；未实现真实 Flutter 客户端加载器、签名验证或客户端回退。 | T、H、R |
| 3.2.5.4 / **SRS-CAP-05-M04-020** 硬件状态监控 | 资产详情 → 监控；计算资源 → 宿主信息 | `GET /assets/{id}/monitor`、`POST /observations` | `AssetMonitoring`、`AlertService.observation`、`AssetsPort` | 本地同项目/资产/模式的最新观测，保留指标、来源、时间；过期/无数据 UNKNOWN，模拟不混入真实。宿主关系来自任务引用，真实连续采样和物理机/VM 实态待联调。 | M、A、H、R |
| 3.2.5.4 / **SRS-CAP-05-M04-021** 资产清单同步 | 资产管理 → 同步资产；支撑页 → 采集计划 | `/assets/sync/preview`、`/assets/sync/confirm`；`/collection-schedules`、`/collection-runs` | `AssetService`、`AssetPreviewEntity`、`CollectionScheduleService/Worker` | 本地明确 1～100 个同步目标、服务身份、周期调度、版本/保护字段和预览幂等。自动 SYNC 只生成 PREVIEW_READY 待确认差异，不自动覆盖台账；人工另行确认，不因未返回而删资产。真实来源、频率和实态仍待联调。 | A、C、H、R |
| 3.2.5.4 / **SRS-CAP-05-M04-022** 部署状态跟踪 | 任务历史、运行详情、逐目标与日志 | `GET /runs`、`/runs/{id}`、`/runs/{id}/logs` | `TaskRepository`、`TaskWorker`、`TaskModels` | 本地持久化任务版本/目标/执行结果，受理、运行与完成分开；失联租约进入 UNKNOWN，禁止自动重放。当前成功结果仅为明确模拟结果，真实流水线回执待联调。 | T、P、H、R |
| 3.2.5.4 / **SRS-CAP-05-M04-023** 告警与通知 | 告警管理、顶部通知中心 | `/alarm-rules`、`/observations`、`/alarms`、`/notifications` | `AlertService`、`FailureObserver`、`AssetMonitoring` | 本地指标规则、失败观察、去重、处理记录及个人站内通知；已读/已处理与观测恢复分离。外部邮件/Webhook 未接入，返回 NOT_CONFIGURED；真实监控事件和投递回执待联调。 | M、T、H、R |
| 3.2.5.5 / **SRS-CAP-05-M05-024** 资产领域模型 | 七个业务页面、资产关系及支撑配置 | `/model/catalog`、`/relationships`；资产/任务/运行/观测 API；`AssetsPort` | `RelationshipController`、`AssetEntity`、`TaskModels`、`TaskRepository`、`AlertService` | 已提供本地概念→模型/API 目录、四类资产与任务映射、具外键的资产关系 CRUD；同项目同模式、非自引用、版本删除均受控。上级规制模型及组件有效版本、完整属性对应和真实资源操作仍待逐项核对；概念目录不表示现场联调完成。 | A、T、P、H、R |
| 3.2.5.5 / **SRS-CAP-05-M05-026** 资产 API 工具封装 | MCP 客户端，非独立业务页面 | `/mcp`，已登记的 9 个工具 | `McpConfiguration`、`Access`、`AssetService`、`TaskService` | 本地 MCP 工具目录、输入模式、同服务授权/审计及结构化结果；仅覆盖当前登记的 API 子集，工具端到端交互和真实资源回执需另收集验证证据。 | A、T、H、R |

## 跨域与非功能约束

| 来源 | 当前落实 | 仍需验证或确认 |
| --- | --- | --- |
| `IF-EXT-001`、CAP-12、SEC-001/002/006 | 本地 Keycloak realm、OIDC JWT、受众/有效性校验、项目授权表、人员/服务身份区别；服务端拒绝与前端入口控制。 | 学校身份源的实际声明、映射、注销和授权策略；五类开发账号不等于学校账号或正式岗位授权。 |
| `IF-EXT-008` | 连接元数据、凭据引用、资源身份模型、真实未配置错误；执行适配接口。 | 实际 PVE/云接口版本、地址、查询/变更范围、容量口径、超时查证与真实回执。填写端点不等于已经实现适配器。 |
| `IF-INT-011`、`IF-INT-006`、`IF-INT-012` | 计划/目标/制品引用与运行快照、观测/告警关联、MCP 复用原业务服务。 | 与平台流水线、监控、质量及学校现有系统的真实协作；本工程不是全平台七分系统和十公共模块的完整替代。 |
| CAP-03、CAP-15 | 制品和文档只记录权威引用；本工程不管理第二套源码、制品二进制或文档正文版本。 | 仓库/知识库可见性、内容摘要、批准/定版状态的权威查询及跨域授权。 |
| `SRS-PRF-001～004` | JPA 数据库筛选/分页、source 过滤及受控排序；任务受理/进度/结果、独立 worker 与持久化队列。最新分页代码须随最终构建回归。 | 200 名有效用户、40 有效 QPS、1.5 秒页面指标须在冻结负载、数据规模与规定网络下测量；页面存在不等于达标。 |
| `SRS-RES-001` | PostgreSQL 持久卷及备份/恢复脚本。 | 甲方实际提供、获授权、有效可用的 40TB 资源及代表性读写；本地卷不能代替容量验收。 |
| ENV、CST、SUP、PKG 相关条款 | 程序/配置/数据分离、源码、依赖版本与镜像摘要、迁移、安装和使用资料。 | 国产目标架构、Firefox/Chrome/奇安信兼容、开源审查、正式性能/安全/可靠性报告及项目签审。 |

## 产品补充与迭代顺序

手动登记、三格式导入导出、人工同步预览确认、任务调度、连接配置和个人通知是本轮独立产品的具体实现方式，不改变 SRS 的来源归属。现有独立采集计划已补授权范围、模拟自动发现及同步预览调度。后续优先补真实资源适配、受控制品及批准计划查询，再开展现场回执、客户端兼容和上级规制模型核对。采集计划与部署任务的调度分别持久化，二者不互相替代。

真实接入必须同时满足业务权限和原系统权限；查询权不自动取得创建/删除权。未配置、未知、失败、过期及未完成回写均不得计为真实成功。
