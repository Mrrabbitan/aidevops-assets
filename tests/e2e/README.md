# 浏览器回归

场景使用真实 OIDC Authorization Code + PKCE 登录，访问七个路由，通过真实后端新增资产，并刷新确认持久化。不注入 Token、不拦截接口、不使用前端假数据。

先启动工程服务和前端；在 frontend 目录执行：

```
npm ci
npx playwright install chromium
E2E_BASE_URL=http://localhost:19385 E2E_USERNAME=admin E2E_PASSWORD='本地开发账号密码' npm run test:e2e
```

`E2E_BASE_URL` 可切换到本工程生产入口 `http://localhost:19380`，对应回调必须已注册。密码由环境变量注入，测试代码不保存密码。测试会在演示项目创建名称包含时间戳的软件资产供验证，原始资产不受影响。

成功运行会在 `docs/screenshots/overview.jpg`、`docs/screenshots/assets.jpg` 保存全页截图；失败保留 Playwright trace 和截图到 `tests/e2e/results`，HTML报告输出到 `tests/e2e/report`。首次安装浏览器可能需要网络访问。
