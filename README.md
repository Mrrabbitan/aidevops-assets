# AIDevOps 资产台账

独立、可迭代的资产台账前后端工程。覆盖总览、资产管理、计算资源、环境部署、应用部署、应用分发、告警管理七类页面，以及关系、采集计划、连接配置、通知和审计。

## 启动

已安装 Docker Compose 时，在本目录执行：

```sh
./scripts/start.sh
```

访问 <http://localhost:19380>。首次构建需要访问 Maven、npm 和容器镜像仓库。

| 开发账号 | 密码 | 项目内默认权限 |
| --- | --- | --- |
| admin | Admin-local-2026! | demo、sandbox 内的管理权限 |
| ops | Demo-local-2026! | demo 运维与发布确认 |
| developer、qa、project | Demo-local-2026! | demo 查看 |

这些账号只存在于本工程开发 Keycloak。生产配置不运行开发身份源、不创建演示授权。

## 已实现的边界

资产、分类、版本、入库、CSV/JSON/XLSX 导入导出、同步差异确认、关系、任务、规则、告警、通知和审计均持久化至 PostgreSQL。前端使用真实 HTTP API 和 OIDC 登录，没有浏览器内存后端回退。

模拟发现与执行仅用于 `demo` 项目，始终显示 `SIMULATED`。真实连接缺失返回 `NOT_CONFIGURED`；当前代码不会操作真实 PVE、K8S、Flatpak 或 Flutter 设备，也不会把台账写入当作实际部署成功。真实采集、设备操作和外部通知需要按现场接口实现适配器并联调。

生产发布绑定冻结计划及确认记录。重复执行采用幂等键，外部结果未知时保留 `UNKNOWN` 并阻止自动重放。模拟运行与真实资产状态分开保存。

## 工程结构

```text
frontend/     Vue 3 + TypeScript + Element Plus + Pinia + ECharts
backend/      Java 21 + Spring Boot 4.1 + Security/OIDC + JPA/JDBC
gateway/     Spring Cloud Gateway 统一入口
infra/        开发身份源、数据库初始化、Nginx 和快速镜像配置
scripts/      启动、测试、备份恢复及本地性能数据工具
tests/        HTTP 集成、MCP、浏览器与性能测试
docs/         设计、OpenAPI、需求追踪、使用、部署和验证记录
```

数据库迁移由 Flyway 管理；API 和独立 worker 使用同一份业务代码。后端任务与周期采集通过数据库租约和锁领取，首次安装不依赖平台流水线。

## 开发与验证

```sh
# 需要 Java 21，或按部署文档使用 Maven 构建容器
./mvnw clean test package

# 使用 Node >=24.12；依赖由 package-lock.json 固定
cd frontend
npm ci
npm test
npm run build

# 完整后端数据库检查与前端构建
./scripts/test.sh

# 本工程服务启动后执行，测试数据全部标记为模拟
python3 tests/integration.py
python3 tests/mcp.py
```

`TaskPostgresTest` 仅在显式设置专用 `_test` 数据库时运行；`scripts/test.sh` 已提供该配置。默认普通单元测试会明确跳过这组数据库测试，不能将跳过称为通过。

## 文档

- [整体架构](docs/architecture.md)与[数据设计](docs/data-design.md)
- [使用说明](docs/user-guide.md)与[部署、备份恢复](docs/deployment.md)
- [OpenAPI 契约](docs/openapi.json)与[21条 CAP-05 需求追踪](docs/requirements-traceability.md)
- [测试报告](docs/test-report.md)与[性能验证说明](docs/performance.md)

完整的21条需求追踪分别记录本地业务、适配契约、模拟验证与真实联调状态。本工程交付不等同于整个 AIDevOps 平台、学校现场国产环境或40TB资源验收。
