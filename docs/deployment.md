# 资产台账部署与开发

以下命令从工程根目录执行。首次安装直接通过 Docker Compose、数据库迁移和身份初始化完成，不依赖本平台的流水线先运行。当前外部真实资源适配尚未实现；部署服务成功不等于 PVE/K8S、Flatpak/Flutter、真实监控或通知已接通。

## 1. 本地完整启动

需要可运行 Linux 容器的 Docker 与 Compose v2。首次源码构建需要访问锁定的基础镜像、Maven 和 npm 仓库，或事先配置批准的内网镜像/依赖缓存。

```sh
cp .env.example .env
./scripts/start.sh
docker compose ps
```

启动脚本执行 `docker compose up -d --build`，从源码构建 API、Gateway、Web，创建 PostgreSQL 持久卷并导入开发 realm。数据库和依赖服务就绪后，访问 [资产台账](http://localhost:19380)。开发账号 `admin / Admin-local-2026!`、`ops / Demo-local-2026!`；它们只属于随工程提供的本地开发身份环境。

| 地址 | 用途 |
| --- | --- |
| `http://localhost:19380` | Nginx 页面入口及 `/api`、`/mcp` 代理 |
| `http://localhost:19381` | API，健康检查 `/actuator/health` |
| `http://localhost:19382` | Gateway，转发 `/api/**` 与 `/mcp` |
| `http://localhost:19383` | 开发 Keycloak，realm 为 `assets` |
| `localhost:19384` | PostgreSQL，业务库 `assets`、开发身份库 `keycloak` |
| `http://localhost:19385` | 需要时单独启动的 Vite 开发页面 |

Compose 对外端口默认仅绑定 `127.0.0.1`。worker 使用同一 API 镜像和独立进程，`ASSETS_ROLE=worker`，不单独发布宿主端口。业务数据位于命名卷 `assets-data`；普通 `docker compose down` 不删除该卷。修改公开身份地址或端口时，要同步修改 `.env` 中 issuer/身份 hostname、前端构建配置和 realm 客户端回调地址；不能仅改宿主端口映射。

```sh
docker compose logs --tail=100 api worker gateway
docker compose stop
docker compose up -d
```

初始业务列表可以为空。通过资产管理的模拟发现获得明确标识的候选、入库后再选择任务目标；手工登记默认为资源状态未知。任务受理后需保持 worker 运行，页面中的排队回执不代表任务已经完成。

## 2. 开发与构建路径

后端要求 Java 21；仓库带 Maven Wrapper，首次使用会取得固定 Maven 3.9.11。前端要求 Node.js **24.12 或以上**，锁定构建镜像使用 24.19.0；当前 shell 若为 24.0，应先切换版本。

```sh
./mvnw -B clean verify
cd frontend
npm ci
npm test
npm run build
```

本地开发可保留 Compose 的 db、keycloak，停止 api、worker、gateway、web，分别运行源码服务。以下本地示例沿用开发数据库；密码应与 `.env` 一致，OIDC issuer 与浏览器看到的地址保持一致。

```sh
docker compose up -d db keycloak
export DB_URL=jdbc:postgresql://localhost:19384/assets
export DB_USER=assets
export DB_PASSWORD=assets-local-only
export OIDC_ISSUER=http://localhost:19383/realms/assets
export OIDC_JWKS=http://localhost:19383/realms/assets/protocol/openid-connect/certs
export DEMO_ENABLED=true
ASSETS_ROLE=api PORT=19381 ./mvnw -pl backend spring-boot:run
```

另开终端设置相同数据库和 OIDC 环境，执行 `ASSETS_ROLE=worker PORT=0 SERVER_ADDRESS=127.0.0.1 ./mvnw -pl backend spring-boot:run`；`PORT=0` 为本地 worker 分配临时端口，不作为业务入口。Gateway 使用 `SERVER_PORT=19382 BACKEND_URL=http://localhost:19381 ./mvnw -pl gateway spring-boot:run`。前端在 `frontend` 中执行 `npm run dev`，Vite 19385 将 `/api` 代理到 Gateway 19382；可按 `frontend/.env.example` 设置身份及代理配置。避免同时启动占用相同宿主端口的 Compose 和源码进程。

源码 Dockerfile 是可重建路径：`backend/Dockerfile`、`gateway/Dockerfile` 在 Maven 构建阶段产生 JAR，`frontend/Dockerfile` 执行 `npm ci` 和前端构建，基础镜像使用锁定 digest。源码镜像构建跳过单元测试，因此发布前仍要单独执行上述验证。

`infra/Dockerfile.prebuilt-api` 与 `infra/Dockerfile.prebuilt-gateway` 仅把已生成的对应 JAR 放进运行镜像，适合已经完成验证后的本地快速装配。其构建上下文分别应提供 `assets-backend-1.0.0-SNAPSHOT.jar` 或 `assets-gateway-1.0.0-SNAPSHOT.jar`；它们不会编译源码，也不能替代源码构建和测试。更改源码后必须重新构建 JAR，不能沿用旧镜像声称包含新代码。

真实 PostgreSQL 队列测试可通过 `ASSETS_TEST_JDBC_URL`、`ASSETS_TEST_JDBC_USER`、`ASSETS_TEST_JDBC_PASSWORD` 启用。测试库名称必须以 `_test` 结尾；测试会清理任务域表，不能指向业务库。没有该测试环境时，测试报告会明确跳过对应 PostgreSQL 场景。

## 3. 生产外部身份与首次授权

生产单独使用 `compose.production.yml`，**不要叠加开发 compose**：生产文件不创建开发 Keycloak、不初始化 demo 授权，也不内建 PostgreSQL 实例。先提供获批 PostgreSQL 和外部 OIDC，再显式设置以下环境变量；缺少必要变量时 Compose 会拒绝展开配置。

| 变量 | 约定 |
| --- | --- |
| `DB_URL`、`DB_USER`、`DB_PASSWORD` | 获批业务 PostgreSQL JDBC 地址及独立应用账户 |
| `OIDC_ISSUER` | 外部身份 issuer，必须与签发 JWT 的 `iss` 一致 |
| `OIDC_JWKS` | API 容器可访问的可信 JWKS 地址 |
| `PUBLIC_URL` | 正式 HTTPS 页面 origin，同时用于 API 的允许来源 |
| `WEB_PORT` | 可选本机监听端口，默认 19380，由外部 HTTPS 入口代理 |

```sh
docker compose --env-file .env.production -f compose.production.yml config
docker compose --env-file .env.production -f compose.production.yml up -d --build
```

生产文件固定 `DEMO_ENABLED=false`。前端构建时取得 `OIDC_ISSUER` 作为 authority；静态页面中的 OIDC 配置变更需要重建 Web 镜像。外部身份需配置 public client `assets-web`、授权码/PKCE、正式 origin 的 `/auth/callback` 及退出回调，签发 API 所需 `assets-api` audience。API 只保存主体 ID 与项目授权，不保存人员密码。部署网络和 HTTPS 入口由现场环境提供，生产文件本身不配置证书、外部数据库备份或身份服务的可用性。

首次生产安装不会自动生成任何平台授权。SSO 登录成功仍可能没有项目权限；应由负责身份和数据库的管理员确认实际 OIDC `sub` 与项目范围，通过受控 SQL 导入首位授权管理员，之后由其维护项目授权。以下占位值必须替换为已经核实的主体与项目，不能用姓名或未经确认的用户名代替 `sub`：

```sql
INSERT INTO access_grants(principal_id,project_id,permissions,enabled,service)
VALUES ('<verified-oidc-sub>','<approved-project-id>','["*"]',true,false)
ON CONFLICT(principal_id,project_id)
DO UPDATE SET permissions=excluded.permissions,enabled=true,service=false;
```

该授权仅在指定项目有效；新增项目需要另行授权。日常人员按实际操作授予读取、维护、执行或确认权限；周期任务使用单独 `service=true` 主体，按项目授予所需权限，不能套用人员账号或因角色名称自动放行。首位管理员及服务授权应纳入部署记录；已有授权管理员也可通过 `/api/v1/access-grants` 受控导入。

真实连接元数据、凭据引用、网络通道、制品权威来源、执行/探测协议均需现场确定。当前 PVE、K8S、Flatpak、Flutter、真实监控和邮件/Webhook 适配器仍待实现与联调；连接页填写 URL、启用元数据或单击测试不会令这些能力自动可用。真实任务执行、真实采集计划启用仍会返回未配置，不能将其改成写台账后返回成功。

## 4. 双库备份、恢复与升级

本地备份脚本生成 PostgreSQL custom-format 业务备份及同名 `.keycloak` 身份库备份，保存在 `backups/`，文件权限由脚本设为仅当前用户可读写。两次 dump 不是跨数据库统一快照；需要一致的恢复点时，安排维护窗口，暂停业务和身份写入后执行：

```sh
docker compose stop web gateway api worker keycloak
./scripts/backup.sh
docker compose up -d keycloak api worker gateway web
```

完整恢复前先保留现有备份；现有脚本要求明确设置 `CONFIRM_RESTORE=yes`，会替换目标库对象。如果业务备份旁存在同名 `.keycloak` 文件，脚本也恢复身份库：

```sh
CONFIRM_RESTORE=yes ./scripts/restore.sh backups/assets-YYYYMMDD-HHMMSS.dump
```

只恢复业务库时，保持身份库不变，停应用后单独执行：

```sh
docker compose stop web gateway api worker
docker compose exec -T db pg_restore -U assets -d assets --clean --if-exists --no-owner < backups/assets-YYYYMMDD-HHMMSS.dump
docker compose up -d api worker gateway web
```

只恢复本地开发身份库时，停止 keycloak 及应用，向 `keycloak` 数据库单独执行 `pg_restore`，再重启身份和应用：

```sh
docker compose stop web gateway api worker keycloak
docker compose exec -T db pg_restore -U assets -d keycloak --clean --if-exists --no-owner < backups/assets-YYYYMMDD-HHMMSS.dump.keycloak
docker compose up -d keycloak api worker gateway web
```

独立恢复后必须核对 `access_grants.principal_id` 与身份主体 `sub` 的对应关系，并重新登录验证。生产使用外部身份时，其数据库、realm/client 配置、签名密钥和恢复流程由身份服务负责人管理，不能用本地 `.keycloak` 文件覆盖正式身份源。生产业务数据库使用获批备份系统，保留与应用镜像及 Flyway 版本匹配的恢复记录。

升级前备份并验证待升级镜像，先由 API 正常启动执行追加迁移，再启动对应 worker；不要删除 Flyway 历史或改写已部署迁移绕过校验。应用镜像回退只能用于与当前 schema 兼容的版本；涉及不兼容结构时，按匹配备份和镜像组合恢复。重启不会自动重放 `UNKNOWN` 执行，先核查运行证据；周期采集和周期任务跳过停机积压。

## 5. 本地检查与交付边界

先检查数据库、身份登录、API 健康和 Gateway，再用有权项目执行模拟发现→入库→任务校验/执行→逐目标日志、同步预览→人工确认、告警/站内通知等链路。可通过运行 ID、审计与 `X-Request-ID` 关联排查；只启动页面或看到登录成功不代表 worker 和业务闭环已验证。

本地部署不验证 40 TB 容量、灾备切换、真实设备性能、全 SRS 或合同验收。生产接入需要在批准环境中逐项实现并验证真实资源、制品、客户端、监控、通知与组织身份适配，相关结果应作为独立验收证据保留。
