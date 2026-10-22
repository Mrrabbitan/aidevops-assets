"""Offline guard/fixture checks only; never starts Compose or sends HTTP traffic."""
import importlib.util
import csv
import io
import re
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("seed_load", ROOT / "scripts/seed-load.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class SeedSafetyTest(unittest.TestCase):
    def test_explicit_confirmation_is_required_before_any_command(self):
        with patch.object(seed, "local_guard") as guard:
            with self.assertRaises(seed.SeedError):
                seed.main([])
            guard.assert_not_called()

    def test_repository_and_symlink_outputs_are_rejected(self):
        with self.assertRaises(seed.SeedError):
            seed.validate_output(str(ROOT / "tokens.json"))
        with tempfile.TemporaryDirectory() as directory:
            link = Path(directory, "tokens.json")
            link.symlink_to(ROOT / "some-token.json")
            with self.assertRaises(seed.SeedError):
                seed.validate_output(str(link))

    def test_external_issuer_fails_before_contacting_identity(self):
        config = {"name": "aidevops-assets", "services": {"api": {"environment": {"OIDC_ISSUER": "https://example.invalid/realms/assets", "DEMO_ENABLED": "true"}}}}
        with patch.object(seed, "command", return_value=json.dumps(config)), patch.object(seed, "request") as request:
            with self.assertRaises(seed.SeedError):
                seed.local_guard()
            request.assert_not_called()

    def test_asset_seed_is_isolated_and_unknown(self):
        captured = []
        def fake_command(args, data=None):
            captured.append((args, data))
            return '["seed-id"]' if data is None else ''
        with patch.object(seed, "command", side_effect=fake_command):
            self.assertEqual(["seed-id"], seed.seed_assets(4))
        payload = captured[0][1]
        self.assertIn('COPY load_asset_stage', payload)
        columns = re.search(r"COPY load_asset_stage \(([^)]+)\) FROM STDIN", payload).group(1).split(",")
        body = payload.split("FROM STDIN WITH (FORMAT csv);\n", 1)[1].split("\\.\n", 1)[0]
        rows = [dict(zip(columns, row)) for row in csv.reader(io.StringIO(body))]
        self.assertEqual(4, len(rows))
        self.assertTrue(all(row["mode"] == "SIMULATED" and row["resource_status"] == "UNKNOWN" for row in rows))
        self.assertTrue(all(row["ip"] == "" for row in rows))
        # PostgreSQL CSV treats an unquoted empty field as NULL. Empty IP must
        # remain explicitly quoted because V2 correctly declares it NOT NULL.
        self.assertIn('"load-fixture","",', body)
        self.assertIn('ON CONFLICT (id) DO NOTHING', payload)

    def test_token_file_has_private_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, "tokens.json")
            seed.write_private(target, {"accessTokens": [{"accessToken": "not-a-real-token"}]})
            self.assertEqual(0o600, os.stat(target).st_mode & 0o777)
            self.assertEqual("not-a-real-token", json.loads(target.read_text())["accessTokens"][0]["accessToken"])


if __name__ == "__main__":
    unittest.main()
