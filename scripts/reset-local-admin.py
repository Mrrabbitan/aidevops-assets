#!/usr/bin/env python3
"""Synchronize the bundled local development admin without replacing realm data."""
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def request(url, data=None, token=None, method=None, form=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = (
            "application/x-www-form-urlencoded" if form else "application/json"
        )
        data = (
            urllib.parse.urlencode(data).encode()
            if form else json.dumps(data).encode()
        )
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def main():
    container = subprocess.check_output(
        ["docker", "compose", "ps", "-q", "keycloak"], cwd=ROOT, text=True
    ).strip()
    if not container:
        raise RuntimeError("请先执行 docker compose up -d db keycloak，并等待身份服务就绪")
    info = json.loads(subprocess.check_output(
        ["docker", "inspect", container], text=True
    ))[0]
    labels = info["Config"].get("Labels", {})
    if (labels.get("com.docker.compose.project") != "aidevops-assets"
            or Path(labels.get("com.docker.compose.project.working_dir", "/")).resolve() != ROOT):
        raise RuntimeError("拒绝修改：身份服务不属于当前本地工程")
    env = dict(item.split("=", 1) for item in info["Config"]["Env"] if "=" in item)
    hostname = urllib.parse.urlparse(env.get("KC_HOSTNAME", ""))
    if hostname.scheme != "http" or hostname.hostname not in ("localhost", "127.0.0.1"):
        raise RuntimeError("拒绝修改：仅允许本工程 HTTP 环回开发身份源")
    ports = info["NetworkSettings"]["Ports"].get("8080/tcp") or []
    binding = next((p for p in ports if p["HostIp"] == "127.0.0.1"), None)
    if not binding:
        raise RuntimeError("拒绝修改：身份服务必须仅绑定本机环回端口")
    base = "http://127.0.0.1:" + binding["HostPort"]
    session = request(base + "/realms/master/protocol/openid-connect/token", {
        "client_id": "admin-cli", "grant_type": "password",
        "username": os.getenv("ASSETS_KC_ADMIN", env.get("KC_BOOTSTRAP_ADMIN_USERNAME", "bootstrap-admin")),
        "password": os.getenv("ASSETS_KC_ADMIN_PASSWORD", env.get("KC_BOOTSTRAP_ADMIN_PASSWORD", "")),
    }, form=True)["access_token"]
    users = request(base + "/admin/realms/assets/users?username=admin&exact=true", token=session)
    if len(users) != 1 or users[0]["id"] != "11111111-1111-4111-8111-111111111111":
        raise RuntimeError("拒绝修改：未找到工程内置开发账号 admin")
    request(base + "/admin/realms/assets/users/" + users[0]["id"] + "/reset-password",
            {"type": "password", "value": "123456", "temporary": False}, session, "PUT")
    request(base + "/realms/assets/protocol/openid-connect/token", {
        "client_id": "assets-web", "grant_type": "password", "username": "admin",
        "password": "123456", "scope": "openid profile email",
    }, form=True)
    print("本地开发账号 admin 密码已同步为 123456，登录验证通过；台账与其他用户保留。")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print("身份服务操作失败（HTTP " + str(error.code) + "）；请核对本地引导账号或服务日志。", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
