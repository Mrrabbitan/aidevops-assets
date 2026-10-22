# 本地性能数据与 k6 验证方法

本文提供可复现的数据生成器和 API 负载脚本，**没有据此执行 200 用户或 40 QPS 的真实压力测试，也不声明达到合同性能指标**。本地 HTTP 功能闭环/MCP 测试与性能验收是不同证据。正式条件仍需按 SRS 冻结环境、数据量、接口、读写模型和有效业务判定后现场验证。

本轮已完成的验证范围：5 项生成器离线安全/数据测试、JavaScript 语法及离线 k6 契约检查，以及本机 **2 个不同测试用户 / 100 条 SIMULATED 合成资产**的数据与身份生成烟测；该烟测已成功，私有 `/tmp` 令牌文件权限为 0600。没有执行 200 用户、40 QPS 的高负载，未测得网页 1.5 秒指标；这些小规模结果不能外推为正式性能通过。

## 1. 文件、环境和边界

| 文件 | 用途 |
| --- | --- |
| `scripts/seed-load.py` | 仅为本工程本地开发 Compose 生成合成资产、独立 Keycloak 测试用户和对应项目授权，输出私有短期令牌文件 |
| `tests/load/assets.js` | k6 的 `qps` 或 `online` 两种独立模式，读写默认 80/20 |
| `tests/load/test_seed_safety.py` | 生成器的离线拒绝/权限/数据隔离测试，不访问服务 |
| `tests/load/test_k6_contract.mjs` | 使用内存桩执行 k6 输入/身份验证约束，不发送 HTTP |

需要运行本仓库的开发 Compose，以及本机 Python 3、k6；离线 JavaScript 校验还使用 Node.js。记录 `k6 version`、Python/Node 版本、提交号、镜像摘要和数据库迁移版本，不能把不同版本结果混用。k6 负载仅针对 `http://localhost:19382`，身份服务固定为 `http://localhost:19383/realms/assets`，项目固定为 `demo`。脚本拒绝外部地址、外部 issuer 和其他项目。

生成器检查 Compose 项目名称、api 的 DEMO_ENABLED/OIDC_ISSUER、Keycloak 地址，以及数据库容器的项目标签和 checkout 目录。它不会修改生产数据库或连接真实 PVE/K8S/设备。需要显式 `--confirm-local`，否则在命令/网络访问前退出。

## 2. 先做小数据、短时检查

从工程根目录操作。以下命令仅为示例，不表示已经执行负载。

```sh
python3 scripts/seed-load.py --confirm-local --users 3 --assets 100 \
  --output /tmp/aidevops-assets-smoke.tokens.json

k6 run --env TOKENS_FILE=/tmp/aidevops-assets-smoke.tokens.json \
  --env MODE=qps --env USERS=3 --env RATE=2 --env DURATION_SECONDS=5 \
  --env SUMMARY_FILE=/tmp/aidevops-assets-smoke-qps.json tests/load/assets.js

k6 run --env TOKENS_FILE=/tmp/aidevops-assets-smoke.tokens.json \
  --env MODE=online --env USERS=3 --env RATE=2 --env DURATION_SECONDS=10 \
  --env SUMMARY_FILE=/tmp/aidevops-assets-smoke-online.json tests/load/assets.js
```

小数据检查成功后，再按已确认资源条件安排较大规模。运行前可做纯离线检查：

```sh
python3 -B -m unittest discover -s tests/load -p 'test_*.py'
node --check tests/load/assets.js
node tests/load/test_k6_contract.mjs
```

离线测试只验证拒绝条件和脚本逻辑；不能证明本地身份服务、k6 引擎、数据库导入或性能已经通过。

## 3. 合成数据和身份生成

默认命令生成 200 个测试用户和最多 10000 条本次请求的种子资产：

```sh
python3 scripts/seed-load.py --confirm-local \
  --users 200 --assets 10000 \
  --output /tmp/aidevops-assets-load.tokens.json
```

生成行为如下：

- 数据库操作固定通过本工程 `docker compose exec -T db psql`；以事务临时表 COPY，再按确定的种子 ID `ON CONFLICT DO NOTHING`。重复运行不覆盖手工修改，也不删除此前生成的更多数据；`--assets 100` 不会把已存在的 10000 条缩减为 100 条。
- 四类资产轮流分布，名称 `load-asset-*`、连接器 `load-fixture`、来源 `LOAD_TEST`、项目 `demo`。始终为 **SIMULATED / UNKNOWN**，不设置真实采集时间，不冒充在线设备。入库状态 ADMITTED 只便于查询，不代表资源就绪。
- 种子数据直接进入数据库，没有逐条通过业务创建 API，不作为资产创建延迟、业务审计完整性的证据。k6 的写请求另经真实业务 API，形成正常版本/审计。
- 身份通过本地 Keycloak Admin REST 创建 `load-0001` 等独立用户。已有同名用户只有携带本生成器所有者标记时才会重置随机密码；不覆盖其他已有账号。用户密码仅在进程内短时存在，不打印或写入令牌文件。
- 通过平台 `PUT /api/v1/access-grants/{subject}` 授予本项目 `asset.read`、`asset.write`，`service=false`；不授予管理员或真实资源执行权。每个用户单独获取令牌，校验不同 subject，并调用 `/auth/me` 核对有效身份。

Keycloak 管理员默认使用本地 `bootstrap-admin` 和开发引导密码；平台管理员默认使用开发 realm 的 `admin`。修改过开发密码时，可通过环境变量 `ASSETS_KC_ADMIN`、`KEYCLOAK_ADMIN_PASSWORD`、`ASSETS_ADMIN_PASSWORD` 提供，命令参数和输出不携带密码。生成器只允许本工程开发 realm，Admin REST 端点按 [Keycloak 官方接口](https://www.keycloak.org/docs-api/latest/rest-api/index.html#_users) 使用。

输出 JSON 的结构为：

```json
{
  "schemaVersion": 1,
  "apiBase": "http://localhost:19382",
  "issuer": "http://localhost:19383/realms/assets",
  "projectId": "demo",
  "accessTokens": [
    {"subject": "一个独立主体ID", "username": "load-0001", "accessToken": "仅私有文件中的短期凭据", "expiresAt": 0}
  ],
  "seedAssetIds": ["种子资产ID"],
  "seedAssetRequestCount": 10000
}
```

上例仅解释格式，不能用于实际运行。输出默认 `/tmp/aidevops-assets-load.tokens.json`，以原子替换写入并设置 **0600**。拒绝符号链接、其他用户拥有的已有文件，以及本仓库或其他 Git 仓库内路径。不得提交、打印、粘贴、录屏展示该文件；不要使用会输出 Authorization 的 `--http-debug`。完成后删除私有令牌文件，失效前也不要把它作为测试报告附件。

令牌有过期时间。k6 在 setup 要求令牌至少覆盖配置时长再加 60 秒；不足会拒绝开始。应在生成后立即短时运行，过期后重新生成，不以失效令牌构造失败流量再冒称“有效用户”。该工具不改变 realm 的过期策略，也没有后台刷新长时间压力令牌。

## 4. 两种 k6 模式

### 4.1 `MODE=qps`：固定业务到达率

```sh
k6 run --env MODE=qps --env USERS=200 --env RATE=40 \
  --env DURATION_SECONDS=60 --env READ_PERCENT=80 \
  --env TOKENS_FILE=/tmp/aidevops-assets-load.tokens.json \
  --env SUMMARY_FILE=/tmp/aidevops-assets-qps.json tests/load/assets.js
```

使用 `constant-arrival-rate`，每个迭代只办理一个业务请求：列表/详情读取，或新增模拟软件资产。默认 80% 读、20% 写，以确定的迭代序列分配，避免随机比例漂移。身份令牌轮流使用，预分配/最大 VU 仅是发压资源，**不据此认定 200 个用户同时在线**。到达率与 VU 数是不同参数，参见 [k6 固定到达率说明](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)。

`RATE=40` 是每秒计划发起 40 次业务，不自动等于 40 次成功且鉴权通过。必须看成功计数、实际时间窗口、错误率和 dropped iterations。若要在验收中得到不少于 40 **有效成功** QPS，可在冻结方案中约定适当的发起余量，例如 RATE=45，再按实际成功量判定；不能把配置值当测量值。

### 4.2 `MODE=online`：独立有效用户持续办理业务

```sh
k6 run --env MODE=online --env USERS=200 --env RATE=40 \
  --env DURATION_SECONDS=60 --env READ_PERCENT=80 \
  --env TOKENS_FILE=/tmp/aidevops-assets-load.tokens.json \
  --env SUMMARY_FILE=/tmp/aidevops-assets-online.json tests/load/assets.js
```

只有一个 constant-vus 场景，每个 VU 稳定绑定一个不同 subject，200 VU 必须有 200 个不同的有效人员令牌。首次请求错开，每个周期先 `/auth/me` 核对当前身份仍有效，再办理业务；`online_subjects_validated` 必须等于 USERS，`online_identity_valid` 必须全为成功。默认每个用户 5 秒一轮（USERS/RATE），可用 `ONLINE_PERIOD_SECONDS` 调整；时长至少覆盖两轮。

这是封闭用户模型，响应变慢时吞吐会下降，应报告实测业务 QPS。身份心跳是额外 HTTP 请求：200 用户/5 秒约增加 40 次身份检查/秒，因此总 HTTP QPS 并不等于业务 QPS。该模式验证不同有效人员上下文持续可访问，不代替 200 个真实浏览器的页面/SSO 易用性验收。VU 的独立编号语义参见 [k6 execution API](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/)。

两个模式应分别记录结果，不能把 qps 模式的预分配 VU 当在线人数，也不能把 online 模式总 HTTP 吞吐当核心业务有效 QPS。

### 两种模式共同的 setup

setup 对输入文件中的**每一个**令牌执行以下核验，即使数量超过 USERS：解码获得 subject/issuer/exp 作为预检查、拒绝重复主体、拒绝过期风险，然后通过服务端 `/auth/me` 验证真实签名/受众/授权结果、subject 一致、人员身份、demo 范围及本次读写所需权限。单个管理员令牌复制 200 次会被拒绝，不会被计为 200 个用户。解码 JWT 本身不作为可信鉴权。

## 5. 指标、错误与报告

脚本输出的私有凭据不会进入 summary。默认汇总文件为 `/tmp/aidevops-assets-load-summary.json`，包含：

| 指标 | 口径 |
| --- | --- |
| `core_attempted_business_requests` | 仅核心业务尝试数，不含 setup/身份心跳 |
| `core_successful_business_requests` | HTTP 状态和业务响应均正确的成功数；401/403/失败/响应结构错误均不计成功 |
| `core_business_success` | 业务成功率，默认阈值为 100% |
| `business_endpoint_error` | 业务错误率，默认阈值为 0 |
| `assets_list_ms` / `assets_detail_ms` / `assets_create_ms` | 各接口 avg/min/**max/p95**/p99 实测响应时间 |
| `assets_list_error` / `assets_detail_error` / `assets_create_error` | 各接口错误比例；未被该模型执行的接口无样本，不补造结果 |
| `core_read_attempts` / `core_write_attempts` | 实际读写尝试数，核对 80/20 或配置比例 |
| `dropped_iterations` | 到达率模式因 VU/响应等原因未发出的迭代，默认不允许丢弃 |
| `online_subjects_validated` / `online_identity_valid` | 在线模式的不同主体已验证数量及持续身份有效性 |

summary 同时保留按配置窗口和 k6 实际运行窗口计算的成功 QPS。gracefulStop 可能延长完成时间，不能只取有利的分母；保留 raw metrics 和时序输出，冻结实际判定窗口。内置 `http_reqs` 还含身份/setup 请求，不用于证明核心有效 QPS。

默认不设置“API p95≤1.5秒就通过网页指标”的阈值。测试报告应附：软件提交与构建、宿主和容器 CPU/内存/架构、网络、数据库规模、索引/迁移、k6 版本和运行命令、有效主体数、精确读写比例、每接口最大/p95/错误、成功 QPS、资源使用、失败/丢弃及复测。写压会持续新增 `load-write-*` 模拟资产和审计；重复测试前记录库规模，不把增长后的样本当同一数据基线。

## 6. 网页 1.5 秒必须单独测量

SRS 的网页响应时间从**用户操作触发**到**主要结果可用**，不是单个 API 的网络时间。本 API 脚本在 summary 明确写 `webpageResult=NOT_MEASURED`，不作网页达标结论。

网页验证应使用真实 Chromium/Firefox/奇安信等确认组合，从已登录页面开始，按代表性操作（进入资产列表、筛选、打开详情、提交修改）记录点击/触发时刻，等待对应主要数据渲染且加载状态结束再结束计时，并关联请求追踪 ID、版本、数据量与截图/浏览器追踪。需计入网络、网关、多个 API、解析与页面渲染；记录失败和超时。浏览器自动化工具的固定等待或 API p95 不能替代此测量。该浏览器计时方案尚需实施和收集证据。

## 7. 合同验证边界与清理

本地 Docker、环回网络和合成数据不是合同规定的服务器内部万兆、用户侧千兆环境，也没有证明国产硬件/浏览器兼容和 40TB 有效存储。正式 200 有效在线、40 有效 QPS、网页≤1.5秒须在受控条件分别判定；负载工具、身份计数与成功业务口径应在执行前确定。

生成器不自动删除用户或资产，避免误删已有业务及审计；它也不会把模拟资产变为真实。反复生成会更新归本生成器所有的 load-* 测试用户随机密码及短期令牌。停止使用后删除 `/tmp` 的令牌和敏感运行输入；若要彻底清空开发数据，应先备份并按本地环境的数据重置流程办理，不在此脚本中隐式删除持久卷。
