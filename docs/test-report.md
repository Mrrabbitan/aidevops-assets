# 资产台账开发验证报告

验证日期：2026-10-22（北京时间）。对象：本仓库首次开发交付；软件版本 `1.0.0-SNAPSHOT` / 前端 `1.0.0`。本报告记录已经执行的检查，未把测试源码、模拟成功或接口存在视为真实现场联调通过。交付归档的提交号及 SHA-256 见归档随附说明。

## 环境与构建

本机 macOS / arm64，Docker Compose 开发拓扑：前端 Nginx、网关、API、独立 worker、PostgreSQL、Keycloak 六个服务。最终入口 `http://localhost:19380`；所有对外开发端口绑定本机环回地址。数据库已应用 Flyway V1～V7。

后端在 Maven 3.9.11 / Temurin Java 21 构建容器中执行 `clean test package`；数据库测试连接独立 `assets_test`，不是业务数据库。前端使用 Node 24.19.0，完成 Vue/TypeScript 检查和 Vite 生产构建。最终执行 `scripts/start.sh` 从源码构建并启动全部服务，正常 OIDC 浏览器回归运行于该容器构建。

依赖依据 `pom.xml`、前端 `package-lock.json` 固定；Maven 解析后的 CycloneDX 清单在 `docs/sbom`，基础镜像摘要在 `docs/image-lock.json`。Spring Boot 4.1.1、Spring Cloud 2025.1.3、PostgreSQL 17、Keycloak 26.8.1；具体包版本以清单为准。

## 实际结果

| 检查 | 执行结果 | 可复核证据 |
| --- | --- | --- |
| 后端单元与 PostgreSQL 测试 | **67 通过，0 失败，0 跳过**；含 6 项实际 PostgreSQL 并发/租约/权限测试 | `docs/test-results/backend.json`、后端测试源码 |
| 前端单元与组件测试 | **19 通过，0 失败，0 跳过** | `docs/test-results/frontend.json` |
| 运行栈 HTTP 业务集成 | **45 项全部通过** | `docs/test-results/integration.json`、`tests/integration.py` |
| MCP 真实协议与授权检查 | **4 项全部通过** | `docs/test-results/mcp.json`、`tests/mcp.py` |
| Chrome 端到端 | **1 个完整场景通过**，正常登录、七类页面、创建后刷新持久化、模式可见及无运行时错误 | `docs/test-results/browser.json`、`tests/e2e/assets.spec.ts`、`docs/screenshots` |
| 备份与隔离恢复 | 两个数据库备份恢复到独立测试库，**8 组表记录数一致**，原业务库未被替换 | `docs/test-results/restore.json` |
| 数据生成器离线检查 | **5 项通过**；另有 k6 身份/统计契约离线检查通过 | `tests/load`、`docs/performance.md` |
| 性能数据生成烟测 | **2 个不同身份、100 条模拟资产生成成功** | `scripts/seed-load.py`、`docs/performance.md`；私有令牌不随交付 |
| npm 依赖审计 | **0 个已知漏洞**（含开发依赖，本次仓库查询结果） | `docs/test-results/npm-audit.json` |
| 生产配置结构 | 独立 Compose 配置解析通过，必填外部身份/数据库变量保留强制要求 | `compose.production.yml`；未部署生产 |

HTTP 检查覆盖：无令牌/无效令牌拒绝、项目范围与只读权限、重复发现、版本入库、完整硬件字段及序列号/MAC 冲突、关系、旧同步预览拒绝、人工字段保护、CSV 公式中和、XLSX 导出及逐行导入结果；观测、告警去重/处理/恢复与站内通知；集群/虚拟机/环境/应用/分发任务、生产确认、幂等、逐目标部分失败、真实未接入拒绝；采集计划与同步预览调度、分页排序、审计和 OpenAPI。

数据库测试覆盖多进程领取、租约 fencing、执行前授权撤销、冻结目标变更、幂等冲突，以及模拟结果不污染真实状态。MCP 检查包含官方 SDK 握手与目录、授权查询、只读变更拒绝及跨项目拒绝。没有使用前端接口拦截、假 JWT 或内存服务替代这些验证。

## 本次修复并复核的问题

- 开发 Keycloak 令牌默认作用域补齐 `basic`，保证 subject 声明；服务端仍拒绝缺少身份的令牌。
- Nginx 的 `/assets` 静态目录与同名页面路由冲突已修复，直接刷新回到 SPA 页面并保留开发端口。
- 状态标签关闭入场动画，修复首帧空白；真实组件测试、浏览器可见性断言与最终截图均已核对。
- Vite / Vitest 更新至固定补丁版本，当前 npm 审计结果为零。序列号与标准化 MAC 的数据库唯一索引纳入 V7 并完成回归。

## 复现入口

```sh
./scripts/start.sh
./scripts/test.sh
python3 tests/integration.py
python3 tests/mcp.py
E2E_BROWSER_CHANNEL=chrome E2E_BASE_URL=http://localhost:19380 \
  E2E_PASSWORD='Admin-local-2026!' npm --prefix frontend run test:e2e
```

浏览器测试需本机 Chrome 或安装 Playwright 浏览器，Node 版本需符合 `frontend/package.json`。`scripts/test.sh` 明确准备专用数据库；不带数据库配置直接运行 Maven 时会跳过 PostgreSQL 组，不能复用本次“0 跳过”的结论。备份恢复操作见部署文档，覆盖业务和身份数据库。集成测试会保留明确标记的测试台账和审计，以便复查。

## 交付判定与待办边界

- **业务已实现**：七类页面及本地台账、权限、版本/确认、任务队列、日志/结果、告警、站内通知、审计、备份恢复和接口契约。CAP-05 正式条目经源文件核对为 **21 条**，逐条映射见 `requirements-traceability.md`。
- **适配契约已实现 / 模拟验证通过**：发现、同步及资源执行采用适配层；模拟只用于 demo 项目并携带模式，结果不更新真实部署版本。未配置真实连接返回 `NOT_CONFIGURED`，不是虚构外部成功。
- **真实联调待完成**：学校统一身份、PVE/K8S/流水线/制品/监控、Flatpak/Flutter 客户端及外部通知。真实接口、凭据与执行回执尚未验证。
- **正式验收待完成**：200 有效在线、40 有效 QPS、网页 1.5 秒指标未执行验收负载；40TB、国产目标环境、多浏览器、安全评测及正式签审需现场独立验证。当前没有正式安全渗透或全依赖漏洞扫描结论。

该报告支持当前本地子系统开发交付，不是整个 AIDevOps 平台或现场全部 SRS 条款的合格证书。
