#!/usr/bin/env python3
"""Seed this repository's LOCAL development Compose only; never prints tokens."""
import argparse
import base64
import csv
import datetime as dt
import io
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
API = "http://localhost:19382"
IDP = "http://localhost:19383"
ISSUER = IDP + "/realms/assets"
OWNER = "aidevops-assets-local-load-v1"
PERMISSIONS = ["asset.read", "asset.write"]


class SeedError(Exception):
    pass


def command(args, data=None):
    result = subprocess.run(args, cwd=ROOT, input=data, text=True, capture_output=True)
    if result.returncode:
        # Container errors may contain input SQL. Do not echo captured streams.
        raise SeedError("Local Compose command failed; inspect the service separately.")
    return result.stdout.strip()


def request(url, method="GET", data=None, token=None, form=False, expected=(200, 201, 204)):
    if not (url.startswith(API + "/") or url.startswith(IDP + "/")):
        raise SeedError("Non-local HTTP destination refused.")
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        if form:
            data = urllib.parse.urlencode(data).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        # Refuse redirects: a local endpoint may not forward credentials externally.
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(req, timeout=30) as response:
            if response.status not in expected:
                raise SeedError("Unexpected local HTTP status.")
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        raise SeedError(f"Local HTTP {exc.code} for {method} {urllib.parse.urlparse(url).path}; response omitted.") from None
    except urllib.error.URLError:
        raise SeedError("Local endpoint is unavailable; start the development Compose services.") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def login(realm, client, username, password):
    result = request(IDP + f"/realms/{realm}/protocol/openid-connect/token", "POST", {
        "client_id": client, "grant_type": "password", "username": username,
        "password": password, "scope": "openid profile email",
    }, form=True)
    return result["access_token"]


def claims(token):
    try:
        segment = token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))
    except Exception:
        raise SeedError("Identity service returned a malformed token.") from None


def validate_output(raw):
    supplied = Path(raw).expanduser()
    if supplied.is_symlink():
        raise SeedError("Token output cannot be a symlink.")
    target = supplied.resolve()
    if not target.parent.is_dir():
        raise SeedError("Token output parent directory must already exist.")
    if target == ROOT or ROOT in target.parents or any((parent / ".git").exists() for parent in target.parents):
        raise SeedError("Tokens may not be written inside this or another Git repository.")
    if target.exists() and (not stat.S_ISREG(target.stat().st_mode) or target.stat().st_uid != os.getuid()):
        raise SeedError("Existing token output must be a regular file owned by the current user.")
    return target


def local_guard():
    config = json.loads(command(["docker", "compose", "config", "--format", "json"]))
    if config.get("name") != "aidevops-assets":
        raise SeedError("This is not the expected Compose project.")
    services = config.get("services", {})
    env = services.get("api", {}).get("environment", {})
    if env.get("OIDC_ISSUER") != ISSUER or str(env.get("DEMO_ENABLED", "")).lower() != "true":
        raise SeedError("External issuer or non-development environment refused.")
    if env.get("DB_URL") != "jdbc:postgresql://db:5432/assets" or services.get("gateway", {}).get("environment", {}).get("BACKEND_URL") != "http://api:8081":
        raise SeedError("Remote database/backend routing refused.")
    identity_env = services.get("keycloak", {}).get("environment", {})
    if identity_env.get("KC_HOSTNAME") != IDP or identity_env.get("KC_DB_URL") != "jdbc:postgresql://db:5432/keycloak":
        raise SeedError("Only the local development identity address is allowed.")
    container = command(["docker", "compose", "ps", "-q", "db"])
    if not container or "\n" in container:
        raise SeedError("Expected exactly one running local Compose database.")
    labels = json.loads(command(["docker", "inspect", "--format", "{{json .Config.Labels}}", container]))
    if labels.get("com.docker.compose.project") != "aidevops-assets" or Path(labels.get("com.docker.compose.project.working_dir", "")).resolve() != ROOT:
        raise SeedError("Database does not belong to this repository's Compose checkout.")
    discovery = request(ISSUER + "/.well-known/openid-configuration")
    if discovery.get("issuer") != ISSUER or discovery.get("token_endpoint") != ISSUER + "/protocol/openid-connect/token":
        raise SeedError("Unexpected or external identity issuer refused.")


def seed_assets(count):
    fields = ["id", "project_id", "name", "type", "category", "owner_name", "environment", "dept", "location", "purpose", "operation", "lifecycle", "mode", "admission", "resource_status", "connector_id", "external_id", "network_scope", "ip", "technical_json", "manual_fields_json", "source", "version", "created_at", "updated_at", "deleted"]
    stream = io.StringIO()
    writer = csv.writer(stream, lineterminator="\n", quoting=csv.QUOTE_ALL)
    now = dt.datetime.now(dt.timezone.utc).isoformat()
    for index in range(1, count + 1):
        external = f"load-seed-{index:06d}"
        identifier = str(uuid.uuid5(uuid.NAMESPACE_URL, OWNER + ":" + external))
        kind = ["SERVER", "CLIENT", "NETWORK", "SOFTWARE"][(index - 1) % 4]
        technical = json.dumps({"seedOwner": OWNER, "cpuCores": 64, "memoryGb": 128, "diskGb": 1000}, separators=(",", ":"))
        writer.writerow([identifier, "demo", f"load-asset-{index:06d}", kind, "LOAD_TEST", "load-fixture", "TEST", "load-fixture", "LOCAL_ONLY", "Synthetic performance fixture; not an observed device", "{}", "{}", "SIMULATED", "ADMITTED", "UNKNOWN", "load-fixture", external, "load-fixture", "", technical, '{"fields":[]}', "LOAD_TEST", 0, now, now, "false"])
    columns = ",".join(fields)
    sql = ("BEGIN;\nCREATE TEMP TABLE load_asset_stage (LIKE assets INCLUDING DEFAULTS);\n"
           + f"COPY load_asset_stage ({columns}) FROM STDIN WITH (FORMAT csv);\n"
           + stream.getvalue() + "\\.\n"
           + f"INSERT INTO assets ({columns}) SELECT {columns} FROM load_asset_stage ON CONFLICT (id) DO NOTHING;\nCOMMIT;\n")
    command(["docker", "compose", "exec", "-T", "db", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", "assets", "-d", "assets"], sql)
    query = "select coalesce(json_agg(id),'[]'::json) from (select id from assets where project_id='demo' and source='LOAD_TEST' and connector_id='load-fixture' and mode='SIMULATED' and deleted=false order by id limit 1000) sample"
    sample = json.loads(command(["docker", "compose", "exec", "-T", "db", "psql", "-X", "-At", "-U", "assets", "-d", "assets", "-c", query]))
    if not sample:
        raise SeedError("No seeded asset is readable.")
    return sample


def write_private(target, payload):
    fd, temporary = tempfile.mkstemp(prefix=".assets-load-", suffix=".json", dir=target.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
        os.chmod(target, 0o600)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-local", action="store_true", help="explicitly authorize local development fixture/user creation")
    parser.add_argument("--users", type=int, default=200)
    parser.add_argument("--assets", type=int, default=10000)
    parser.add_argument("--output", default="/tmp/aidevops-assets-load.tokens.json")
    args = parser.parse_args(argv)
    if not args.confirm_local:
        raise SeedError("Refused: pass --confirm-local for this local development Compose only.")
    if not 1 <= args.users <= 1000 or not 1 <= args.assets <= 1000000:
        raise SeedError("users must be 1..1000 and assets 1..1000000.")
    output = validate_output(args.output)
    local_guard()
    master = login("master", "admin-cli", os.getenv("ASSETS_KC_ADMIN", "bootstrap-admin"), os.getenv("KEYCLOAK_ADMIN_PASSWORD", "admin-local-only"))
    realm = request(IDP + "/admin/realms/assets", token=master)
    if realm.get("displayName") != "AIDevOps 资产台账 · 本地开发身份":
        raise SeedError("Realm is not this project's development fixture.")
    platform_admin = login("assets", "assets-web", "admin", os.getenv("ASSETS_ADMIN_PASSWORD", "Admin-local-2026!"))
    profile = request(API + "/api/v1/auth/me", token=platform_admin)
    if profile.get("subject") != "11111111-1111-4111-8111-111111111111" or "demo" not in profile.get("projects", []):
        raise SeedError("Unexpected platform development admin identity.")
    admin_permissions = profile.get("projectPermissions", {}).get("demo", [])
    if "*" not in admin_permissions and "access.manage" not in admin_permissions:
        raise SeedError("Development admin lacks local project authorization management.")
    samples = seed_assets(args.assets)
    access_tokens = []
    subjects = set()
    for index in range(1, args.users + 1):
        username = f"load-{index:04d}"
        if index % 25 == 1:
            master = login("master", "admin-cli", os.getenv("ASSETS_KC_ADMIN", "bootstrap-admin"), os.getenv("KEYCLOAK_ADMIN_PASSWORD", "admin-local-only"))
            platform_admin = login("assets", "assets-web", "admin", os.getenv("ASSETS_ADMIN_PASSWORD", "Admin-local-2026!"))
        found = request(IDP + "/admin/realms/assets/users?" + urllib.parse.urlencode({"username": username, "exact": "true"}), token=master)
        if len(found) > 1:
            raise SeedError("Ambiguous existing load user refused.")
        if found:
            user = found[0]
            if user.get("attributes", {}).get("assetsLoadOwner") != [OWNER]:
                raise SeedError("Existing load-* username is not owned by this seeder; refused to modify it.")
            subject = user["id"]
        else:
            request(IDP + "/admin/realms/assets/users", "POST", {"username": username, "enabled": True, "emailVerified": True, "firstName": "Load", "lastName": str(index), "email": username + "@assets.local", "attributes": {"assetsLoadOwner": [OWNER]}}, master)
            found = request(IDP + "/admin/realms/assets/users?" + urllib.parse.urlencode({"username": username, "exact": "true"}), token=master)
            if len(found) != 1:
                raise SeedError("Created user cannot be resolved uniquely.")
            subject = found[0]["id"]
        uuid.UUID(subject)
        password = "Load-" + secrets.token_urlsafe(24) + "!"
        request(IDP + f"/admin/realms/assets/users/{subject}/reset-password", "PUT", {"type": "password", "value": password, "temporary": False}, master)
        request(API + "/api/v1/access-grants/" + subject, "PUT", {"projectId": "demo", "permissions": PERMISSIONS, "enabled": True, "service": False}, platform_admin)
        token = login("assets", "assets-web", username, password)
        identity = claims(token)
        if identity.get("iss") != ISSUER or identity.get("sub") != subject or subject in subjects:
            raise SeedError("Token issuer/subject is invalid or duplicated.")
        me = request(API + "/api/v1/auth/me", token=token)
        if me.get("subject") != subject or "demo" not in me.get("projects", []) or me.get("service"):
            raise SeedError("A generated human user is not effectively authorized.")
        subjects.add(subject)
        access_tokens.append({"subject": subject, "username": username, "accessToken": token, "expiresAt": identity["exp"]})
    payload = {"schemaVersion": 1, "apiBase": API, "issuer": ISSUER, "projectId": "demo", "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "accessTokens": access_tokens, "seedAssetIds": samples, "seedAssetRequestCount": args.assets}
    write_private(output, payload)
    print(f"Local fixture ready: {len(subjects)} distinct authorized users; requested {args.assets} SIMULATED/UNKNOWN assets.")
    print(f"Private token file: {output} (0600). Tokens/passwords were not printed; regenerate before expiry.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Do not render server payloads, token objects or stack traces.
        print("Seed refused/failed: " + (str(exc) if isinstance(exc, SeedError) else "Unexpected local response; no credentials displayed."), file=sys.stderr)
        sys.exit(2)
