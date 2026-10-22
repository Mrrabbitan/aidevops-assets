# 资产台账前端

Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、ECharts、oidc-client-ts。

## 本地启动

使用 Node 24.12 或更新的 LTS，运行 `npm ci`、`npm run dev`。默认开发端口为 **19385**，避免影响本机既有的 5173 服务。生产入口由整体工程提供，默认端口 **19380**。

复制 `.env.example` 为 `.env.local` 可以修改身份认证和开发代理地址。默认 Keycloak realm 为 `http://localhost:19383/realms/assets`，client 为 `assets-web`；Vite 的 `/api` 代理到 `http://localhost:19382`。回调使用当前页面来源加 `/auth/callback`，身份服务必须登记对应的回调和 Web Origin。已保留原开发端口 5173 的身份服务兼容注册。

`npm run build` 先执行 Vue/TypeScript 类型检查，再生成 `dist/`。`npm run generate:api` 使用 `../docs/openapi.json` 重生成服务类型，任务 DTO 已由 `src/lib/taskApi.ts` 实际使用。`npm test` 验证登录边界、授权头、错误回执、文件上传、分页完整性、项目权限隔离。

## 页面与实现位置

- `src/App.vue`：统一登录、导航、项目切换、通知中心。
- `src/views/Assets.vue`：资产维护、发现、入库、停用、导入/同步预览确认、导出、监控、版本、资产关系。
- `src/views/Tasks.vue`：集群、VM、四类环境、应用部署、三类应用分发；校验、冻结、执行、日志、回退、未知结果核查和调度配置。
- `src/views/Dashboard.vue`：汇总与图表；`Alarms.vue`：告警、规则、通知测试与模拟观测；`Settings.vue`：连接配置、审计及采集计划。
- `src/lib/api.ts`：OIDC、HTTP、错误与项目数据分页；`src/stores/session.ts`：当前项目权限，完全以 `projectPermissions[projectId]` 为准。

## 服务约定

所有请求携带 Bearer Token，基础地址 `/api/v1`，无前端模拟服务或离线成功回退。资产分页使用 `{items,total,page,pageSize}`；其余列表接受直接数组。错误 `{code,message,traceId}` 会显示在操作处。乐观锁写入必须提交当前版本，冲突后需刷新重新核对。

OIDC 使用 Authorization Code + PKCE。身份状态与令牌保存在当前标签页 `sessionStorage`，不写入 `localStorage`。每次项目切换重建业务视图，不继承前一项目的未提交表单。

`SIMULATED` 是后台明确标识的模拟资源和执行模式，不代表真实设备扫描、监控健康、部署或邮件发送。真实适配未配置时直接展示后台错误；监控空值保持未知。告警“处置完成”与设备“已观测恢复”分别呈现。

资产和任务选择器按服务端最大 `pageSize=200` 循环取页，不静默遗漏后续数据。制品摘要、生产配置确认、目标资源权限和最终可用性由前后端共同校验；最终执行以服务端运行编号和状态为准。
