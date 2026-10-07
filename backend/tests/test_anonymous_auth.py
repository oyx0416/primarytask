import os
import unittest
from pathlib import Path
from tempfile import gettempdir
from uuid import uuid4


_test_db_path = Path(gettempdir()) / f"ji-auth-test-{uuid4().hex}.sqlite3"
os.environ["JI_AUTH_DB_PATH"] = str(_test_db_path)

from fastapi.testclient import TestClient
from main import app


class AnonymousAuthTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)

    def tearDown(self) -> None:
        self.client.close()

    def test_client_device_id_cannot_select_or_refresh_an_account(self) -> None:
        victim = self.client.post(
            "/auth/anonymous", json={"device_id": "chosen-device-id"}
        )
        attacker = self.client.post(
            "/auth/anonymous", json={"device_id": "chosen-device-id"}
        )

        self.assertEqual(victim.status_code, 200)
        self.assertEqual(attacker.status_code, 200)
        victim_data = victim.json()
        attacker_data = attacker.json()
        self.assertNotEqual(victim_data["userId"], attacker_data["userId"])
        self.assertNotEqual(victim_data["token"], attacker_data["token"])

        victim_me = self.client.get(
            "/me", headers={"Authorization": f"Bearer {victim_data['token']}"}
        )
        self.assertEqual(victim_me.status_code, 200)
        self.assertEqual(victim_me.json()["userId"], victim_data["userId"])

    def test_valid_bearer_preserves_existing_anonymous_account(self) -> None:
        initial = self.client.post("/auth/anonymous", json={}).json()
        refreshed = self.client.post(
            "/auth/anonymous",
            json={"device_id": "untrusted-other-id"},
            headers={"Authorization": f"Bearer {initial['token']}"},
        )

        self.assertEqual(refreshed.status_code, 200)
        self.assertEqual(refreshed.json()["userId"], initial["userId"])
        self.assertEqual(refreshed.json()["token"], initial["token"])


if __name__ == "__main__":
    unittest.main()
