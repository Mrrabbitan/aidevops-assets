# 资产台账本地执行手册

本手册适用于本仓库的本地开发与演示环境。完整容器方式不要求宿主机安装 Java 或 Node.js；需要修改源码时，再使用后面的本地开发方式。所有命令均在工程根目录执行，除非步骤另有说明。

本地业务数据、任务记录和审计实际保存至 PostgreSQL，登录实际通过随工程提供的 Keycloak 完成。模拟发现、模拟执行仅用于 `demo` 项目，并保留 `SIMULATED` 标识。PVE、K8S、流水线、真实监控、客户端及外部邮件/Webhook 等连接尚未接入时返回 `NOT_CONFIGURED`，不能据此认为真实设备操作成功。

## 1. 准备环境

安装并启动支持 Linux 容器的 Docker，以及 Docker Compose v2。先确认工具和 Docker 服务可用：

```sh
docker version
docker compose version
```

源码构建会下载固定版本的容器镜像、Maven 和 npm 依赖，需要能够访问对应仓库，或配置可用的内部镜像与缓存。首次构建通常比后续启动耗时更长。

先取得代码并进入工程目录：

```sh
git clone https://github.com/Mrrabbitan/aidevops-assets.git
cd aidevops-assets
```

也可在 GitHub 下载源码 ZIP 后解压，进入含 `compose.yml` 的目录。新安装时复制配置；已有 `.env` 时保留原文件，按需对照 `.env.example` 补充字段：

```sh
cp .env.example .env
```

默认端口如下。Compose 的宿主端口均绑定在 `127.0.0.1`。

| 地址 | 用途 |
| --- | --- |
| <http://localhost:19380> | 完整系统页面、`/api` 与 `/mcp` 入口 |
| <http://localhost:19381/actuator/health> | API 健康状态；API 地址为 `http://localhost:19381/api/v1` |
| <http://localhost:19382> | Gateway；转发 `/api/**` 和 `/mcp`，根路径没有业务页面 |
| <http://localhost:19383> | 本地 Keycloak；realm 为 `assets` |
| `localhost:19384` | PostgreSQL；业务库 `assets`，身份库 `keycloak` |
| <http://localhost:19385> | 仅本地开发方式启动的 Vite 页面 |

worker 与 API 使用同一份业务代码，作为独立容器运行，不单独发布宿主端口。PostgreSQL 数据保存于 Compose 命名卷 `assets-data`。

## 2. 完整容器启动与登录

```sh
./scripts/start.sh
docker compose ps
```

启动脚本从源码构建前端、API 和 Gateway，启动数据库、Keycloak、API、worker、Gateway 和 Web，并等待页面 API 与身份配置可访问。看到脚本输出系统地址后，访问 <http://localhost:19380>，通过登录按钮进入身份页面。

| 本地账号 | 密码 | 默认项目权限 |
| --- | --- | --- |
| `admin` | `123456` | `demo`、`sandbox` 内管理权限 |
| `ops` | `Demo-local-2026!` | `demo` 运维与发布确认 |
| `developer`、`qa`、`project` | `Demo-local-2026!` | `demo` 查看 |

`admin / 123456` 是本地开发用账号。生产部署使用外部身份源，不初始化这些账号。Keycloak 管理账号是另外的 `bootstrap-admin`，默认密码来自 `.env` 的 `KEYCLOAK_ADMIN_PASSWORD`，不是业务管理员 `admin`。

已有安装的 Keycloak 将账号保存在数据库内，修改 realm 文件或重新构建不会覆盖现有密码。升级到本版后，在服务启动的情况下执行以下本地同步命令，将已有业务 `admin` 密码设置为 `123456`，随后重新登录：

```sh
python3 scripts/reset-local-admin.py
```

该步骤保留已有数据库和资产数据，无需删除持久卷。脚本仅允许本工程的环回开发身份容器；引导管理员凭据已自行更改时，可通过 `ASSETS_KC_ADMIN` 和 `ASSETS_KC_ADMIN_PASSWORD` 环境变量提供当前值。

## 3. 启动后验证

先确认 API 和身份源就绪：

```sh
curl -fsS http://localhost:19381/actuator/health
curl -fsS http://localhost:19383/realms/assets/.well-known/openid-configuration
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:19380/api/v1/auth/me
```

第一项应返回 `UP`；第二项应返回身份配置 JSON；第三项在没有登录令牌时应返回 **401**，表示入口已接通且接口认证生效。最后一项返回 200 不是未登录场景的预期结果。

登录后选择 `demo` 项目，按以下步骤核对业务：

1. 打开资产总览、资产管理、计算资源、环境部署、应用部署、应用分发和告警管理七类页面。
2. 在资产管理中执行模拟发现，查看候选来源和模拟标识，维护后入库；刷新页面确认记录仍存在。
3. 创建模拟配置，完成校验、所需确认及执行；在运行记录查看排队、进度、日志和逐目标结果。需保持 worker 运行。
4. 查看同步差异，核对后人工确认；查看资产历史和审计。不要把差异预览当作已更新结果。
5. 在告警和通知中核对处理记录。告警处理、站内通知已读与资源真实恢复分别保存。

初始资产列表可以为空。没有观测数据的资产显示未知或无数据；不会自动显示健康。模拟执行不会更新真实资源状态或真实部署版本。

接口接入先阅读[接口开发与联调说明](api-guide.md)与[外部系统接入预留](integrations.md)，字段规范见 [OpenAPI 契约](openapi.json)。在线契约位于 `/api/v1/openapi`，经过相同的身份认证；后续接入应以当前契约、权限和字段校验为准。更多业务操作见[使用说明](user-guide.md)。

## 4. 停止、重启与查看日志

临时停止、重新启动：

```sh
docker compose stop
docker compose up -d
```

源码或构建配置变化后重新构建并启动：

```sh
./scripts/start.sh
```

检查运行状态和最近日志：

```sh
docker compose ps
docker compose logs --tail=100 api worker gateway
docker compose logs --tail=100 db keycloak web
```

持续查看某一服务时使用 `docker compose logs -f worker`，按 `Ctrl+C` 退出日志查看，不会停止容器。只重启后台任务进程可执行 `docker compose restart worker`。任务排队应先核对 worker 日志；结果未知的外部操作不会在重启后盲目重放。

`docker compose down` 会移除本工程容器和网络，保留命名卷。再次启动后继续使用已有数据。删除持久卷会同时丢失业务库和本地身份库，不应作为普通故障排查步骤。

## 5. 基础服务容器化、前后端本地开发

这一方式需要宿主机安装 **Java 21**、**Node.js 24.12 或以上**及 npm。Maven Wrapper 会取得固定 Maven 3.9.11。Python 3 用于集成验证及本地管理脚本。

### 5.1 保留数据库和身份容器

从完整容器方式切换时，先停止会与本地端口冲突的应用容器：

```sh
docker compose stop api worker gateway web
docker compose up -d db keycloak
./mvnw -B clean package -DskipTests
```

以下 API 和 worker 终端都需要先设置相同的环境变量。若 `.env` 中修改过数据库密码，这里的值必须同步修改；`.env` 是 Compose 配置，本地 Java 进程不会自动读取它。

```sh
export DB_URL=jdbc:postgresql://localhost:19384/assets
export DB_USER=assets
export DB_PASSWORD=assets-local-only
export OIDC_ISSUER=http://localhost:19383/realms/assets
export OIDC_JWKS=http://localhost:19383/realms/assets/protocol/openid-connect/certs
export DEMO_ENABLED=true
```

### 5.2 分别启动四个进程

**终端一：API。** 在工程根目录设置上述环境变量，再运行：

```sh
ASSETS_ROLE=api PORT=19381 SERVER_ADDRESS=127.0.0.1 ./mvnw -pl backend spring-boot:run
```

**终端二：worker。** 在工程根目录设置相同环境变量，再运行：

```sh
ASSETS_ROLE=worker PORT=0 SERVER_ADDRESS=127.0.0.1 ./mvnw -pl backend spring-boot:run
```

`PORT=0` 为 worker 分配临时本机端口，worker 的业务是领取数据库任务，不需要浏览器访问。缺少 worker 时，已受理的异步任务可能一直排队。

**终端三：Gateway。** 在工程根目录运行：

```sh
SERVER_PORT=19382 SERVER_ADDRESS=127.0.0.1 BACKEND_URL=http://localhost:19381 ./mvnw -pl gateway spring-boot:run
```

**终端四：前端。** 在工程根目录进入前端目录：

```sh
cd frontend
npm ci
cp .env.example .env.local
npm run dev
```

已有 `frontend/.env.local` 时保留原文件，不重复覆盖。默认页面地址为 <http://localhost:19385>，`/api` 由 Vite 代理到 Gateway 19382；OIDC 使用本地 Keycloak 19383。浏览器访问应统一使用 `localhost`，以匹配 realm 中预设的回调地址。

前端配置变化后重启 Vite；容器静态前端配置变化后需要重建 Web 镜像。不能同时运行占用相同端口的本地进程和 Compose 应用容器。

停止本地进程时，在各自终端按 `Ctrl+C`；返回完整容器方式时执行 `./scripts/start.sh`。

### 5.3 本地验证命令

完整数据库测试和前端检查可从工程根目录执行：

```sh
./scripts/test.sh
```

该脚本使用 Docker 中的 Maven/Java，以及宿主机 npm；会创建独立 `assets_test` 数据库运行数据库测试，并执行前端依赖安装、单元测试和构建。不要把测试数据库配置指向业务库。单独执行 `./mvnw clean test package` 时，如果没有显式配置专用 PostgreSQL 测试库，数据库场景会跳过，应以实际测试报告为准。

完整应用启动后，可执行 HTTP 与 MCP 集成验证。这些检查会在 `demo` 中创建或维护模拟测试记录，并输出 `docs/test-results/` 下的结果：

```sh
python3 tests/integration.py
python3 tests/mcp.py
```

浏览器检查默认访问 Vite 19385；检查完整容器入口时，在工程根目录执行：

```sh
cd frontend
npx playwright install chromium
E2E_BASE_URL=http://localhost:19380 E2E_PASSWORD=123456 npm run test:e2e
```

浏览器测试同样会创建演示记录。首次安装浏览器需要联网。现有验证结果、环境和未验证范围见[测试报告](test-report.md)；本地通过不代替真实现场性能和设备联调。

## 6. 本地备份与恢复

备份脚本同时生成业务库 `.dump` 文件与同名 `.dump.keycloak` 身份库文件，存放在被 Git 忽略的 `backups/` 中。脚本将新备份权限限制为当前用户读写。两次数据库备份不是统一事务快照；需要一致恢复点时，在维护窗口暂停应用及身份写入后执行：

```sh
docker compose stop web gateway api worker keycloak
./scripts/backup.sh
docker compose up -d keycloak api worker gateway web
```

保留脚本输出的文件名和相邻身份备份文件。恢复会替换目标数据库内容，应先备份现有数据；将示例文件名换成实际备份文件：

```sh
CONFIRM_RESTORE=yes ./scripts/restore.sh backups/assets-YYYYMMDD-HHMMSS.dump
```

脚本会停止应用、恢复业务库；如果旁边存在同名 `.keycloak` 文件，也会停止 Keycloak 并恢复身份库，然后重启服务。恢复结束后再次检查健康、登录、资产列表、运行记录和授权范围。只有业务库或身份库的单独恢复方法见[部署与备份恢复](deployment.md)。

升级前先备份，再执行启动构建。Flyway 在 API 启动时执行追加迁移；不要改写已部署迁移或删除迁移历史。数据库结构不兼容时，旧镜像不能直接当作完整回退方案，需要匹配的备份与应用版本。

## 7. 常见错误处理

| 现象 | 核对与处理 |
| --- | --- |
| Docker 命令连接失败 | 启动 Docker，确认 `docker version` 能同时返回客户端和服务端信息。 |
| 构建卡在下载或拉取失败 | 检查网络及镜像/Maven/npm 仓库访问；配置可用缓存或内部仓库后重新执行启动脚本。不要混用未锁定版本绕过错误。 |
| 提示端口已被占用 | 查找占用进程；先停止本工程冲突的源码进程或容器。若更改身份/页面端口，还须同步 OIDC issuer、hostname、前端配置和客户端回调，不能只改端口映射。 |
| 页面 502 或启动等待超时 | 查看 `docker compose ps` 及 API、Gateway、Keycloak 日志；确认数据库健康、迁移成功、API 启动及内部服务地址可达，再重新执行启动脚本。 |
| `admin / 123456` 登录失败 | 确认选用本工程 `assets` realm；已有安装执行 `python3 scripts/reset-local-admin.py`，重新登录。不要删除数据库重置账号。 |
| 回调地址错误、issuer 不一致 | 浏览器统一使用默认 `localhost` 地址；核对 `.env`、前端 authority 与 Keycloak 的允许回调。修改静态前端身份配置后重建 Web。 |
| 接口 401 | 未登录、令牌失效或签发方不匹配；退出后重新登录，并核对身份日志及时间。无令牌请求返回 401 属于正常行为。 |
| 接口 403，登录后项目不可见 | 核对账号的项目及操作授权。`admin` 也仅能访问其授权范围，登录身份成功不等于获得所有项目权限。 |
| 接口 409 或旧预览无法确认 | 对象已被修改或确认快照过期；刷新数据，重新预览和核对后提交。不要反复使用旧版本提交。 |
| 异步任务持续排队 | 确认 worker 正在运行、可以连接同一数据库，并查看该运行 ID 的日志。 |
| 真实执行或连接测试返回 `NOT_CONFIGURED` | 当前外部适配尚未接入；按接口契约完成适配和现场联调。需要演示时，选择 `demo` 及明确的模拟模式。 |
| 数据库密码修改后连接失败 | Compose 环境变量不会自动修改已初始化的 PostgreSQL 用户密码；按数据库管理流程同步已有账号，再更新应用配置。 |
| Node 版本不满足要求 | 使用 Node.js 24.12 或以上，重新执行 `npm ci`；不删除锁文件来绕过依赖约束。 |

排查时保留运行 ID、请求 ID 和必要的脱敏日志。数据库备份、登录令牌和真实凭据不应提交到 GitHub。生产外部身份、凭据、HTTPS、真实资源及现场验收请按[部署说明](deployment.md)、[接口契约](openapi.json)和[需求追踪矩阵](requirements-traceability.md)分别落实。
