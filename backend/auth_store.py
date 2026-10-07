import hashlib
import os
import secrets
import sqlite3
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Optional


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso_now() -> str:
    return utc_now().isoformat()


def today_key() -> str:
    return date.today().isoformat()


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)).strip())
    except (TypeError, ValueError):
        return default


@dataclass
class UserRecord:
    id: str
    device_id: str
    plan: str
    pro_expires_at: Optional[str]
    created_at: str
    last_active_at: str

    @property
    def effective_plan(self) -> str:
        if self.plan != "pro":
            return "free"
        if not self.pro_expires_at:
            return "pro"
        try:
            expires_at = datetime.fromisoformat(self.pro_expires_at)
        except ValueError:
            return "free"
        return "pro" if expires_at > utc_now() else "free"


class AuthStore:
    def __init__(self, db_path: str | None = None) -> None:
        default_path = Path(__file__).with_name("ji_auth.sqlite3")
        self.db_path = Path(db_path or os.getenv("JI_AUTH_DB_PATH", str(default_path)))
        self.hash_salt = os.getenv("JI_AUTH_HASH_SALT", "ji-auth-v1")
        self.init_db()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path)
        connection.row_factory = sqlite3.Row
        return connection

    def init_db(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    device_id TEXT UNIQUE NOT NULL,
                    token_hash TEXT NOT NULL,
                    plan TEXT NOT NULL DEFAULT 'free',
                    pro_expires_at TEXT,
                    created_at TEXT NOT NULL,
                    last_active_at TEXT NOT NULL
                )
                """
            )
            db.execute(
                """
                CREATE TABLE IF NOT EXISTS usage_logs (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    date TEXT NOT NULL,
                    ai_parse_count INTEGER NOT NULL DEFAULT 0,
                    model_used TEXT,
                    created_at TEXT NOT NULL,
                    UNIQUE(user_id, date)
                )
                """
            )
            db.execute(
                """
                CREATE TABLE IF NOT EXISTS activation_codes (
                    id TEXT PRIMARY KEY,
                    code_hash TEXT UNIQUE NOT NULL,
                    plan TEXT NOT NULL DEFAULT 'pro',
                    duration_days INTEGER NOT NULL DEFAULT 30,
                    used_by_user_id TEXT,
                    used_at TEXT,
                    expires_at TEXT,
                    created_at TEXT NOT NULL,
                    is_used INTEGER NOT NULL DEFAULT 0
                )
                """
            )

    def hash_secret(self, value: str) -> str:
        normalized = value.strip()
        return hashlib.sha256(f"{self.hash_salt}:{normalized}".encode("utf-8")).hexdigest()

    def issue_token(self) -> str:
        return secrets.token_urlsafe(32)

    def anonymous_session(self) -> tuple[UserRecord, str]:
        # Client-provided identifiers are not credentials. Always create a fresh
        # server-side identity here; an existing account can only be selected by
        # presenting its bearer token to the auth route.
        server_device_id = str(uuid.uuid4())
        token = self.issue_token()
        token_hash = self.hash_secret(token)
        now = iso_now()

        with self.connect() as db:
            user_id = str(uuid.uuid4())
            db.execute(
                """
                INSERT INTO users (id, device_id, token_hash, plan, pro_expires_at, created_at, last_active_at)
                VALUES (?, ?, ?, 'free', NULL, ?, ?)
                """,
                (user_id, server_device_id, token_hash, now, now),
            )
            row = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()

        return self.row_to_user(row), token

    def get_user_by_token(self, token: str) -> UserRecord | None:
        if not token:
            return None
        token_hash = self.hash_secret(token)
        with self.connect() as db:
            row = db.execute("SELECT * FROM users WHERE token_hash = ?", (token_hash,)).fetchone()
            if not row:
                return None
            db.execute("UPDATE users SET last_active_at = ? WHERE id = ?", (iso_now(), row["id"]))
            user = self.row_to_user(row)
            if user.plan == "pro" and user.effective_plan == "free":
                db.execute(
                    "UPDATE users SET plan = 'free', pro_expires_at = NULL WHERE id = ?",
                    (user.id,),
                )
                return UserRecord(
                    id=user.id,
                    device_id=user.device_id,
                    plan="free",
                    pro_expires_at=None,
                    created_at=user.created_at,
                    last_active_at=iso_now(),
                )
            return user

    def usage_count(self, user_id: str, day: str | None = None) -> int:
        day = day or today_key()
        with self.connect() as db:
            row = db.execute(
                "SELECT ai_parse_count FROM usage_logs WHERE user_id = ? AND date = ?",
                (user_id, day),
            ).fetchone()
        return int(row["ai_parse_count"]) if row else 0

    def increment_usage(self, user_id: str, model_used: str, day: str | None = None) -> int:
        day = day or today_key()
        now = iso_now()
        with self.connect() as db:
            db.execute(
                """
                INSERT INTO usage_logs (id, user_id, date, ai_parse_count, model_used, created_at)
                VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT(user_id, date)
                DO UPDATE SET
                    ai_parse_count = ai_parse_count + 1,
                    model_used = excluded.model_used
                """,
                (str(uuid.uuid4()), user_id, day, model_used, now),
            )
        return self.usage_count(user_id, day)

    def daily_quota(self, user: UserRecord) -> int:
        return _env_int("JI_PRO_DAILY_QUOTA", 50) if user.effective_plan == "pro" else _env_int("JI_FREE_DAILY_QUOTA", 3)

    def usage_payload(self, user: UserRecord) -> dict:
        return {
            "plan": user.effective_plan,
            "proExpiresAt": user.pro_expires_at if user.effective_plan == "pro" else None,
            "usedToday": self.usage_count(user.id),
            "dailyQuota": self.daily_quota(user),
        }

    def user_payload(self, user: UserRecord, token: str | None = None) -> dict:
        payload = {
            "userId": user.id,
            "plan": user.effective_plan,
            "proExpiresAt": user.pro_expires_at if user.effective_plan == "pro" else None,
            "usedToday": self.usage_count(user.id),
            "dailyQuota": self.daily_quota(user),
        }
        if token is not None:
            payload["token"] = token
        return payload

    def redeem_code(self, user: UserRecord, code: str) -> UserRecord:
        code_hash = self.hash_secret(code.upper())
        now = utc_now()
        now_text = now.isoformat()
        with self.connect() as db:
            row = db.execute(
                "SELECT * FROM activation_codes WHERE code_hash = ?",
                (code_hash,),
            ).fetchone()
            if not row:
                raise ValueError("激活码不存在")
            if int(row["is_used"]):
                raise ValueError("激活码已使用")
            if row["expires_at"]:
                try:
                    if datetime.fromisoformat(row["expires_at"]) <= now:
                        raise ValueError("激活码已过期")
                except ValueError as exc:
                    if str(exc) == "激活码已过期":
                        raise

            duration_days = int(row["duration_days"] or 30)
            current_expires_at = None
            if user.effective_plan == "pro" and user.pro_expires_at:
                try:
                    current_expires_at = datetime.fromisoformat(user.pro_expires_at)
                except ValueError:
                    current_expires_at = None
            start_at = max(now, current_expires_at) if current_expires_at else now
            pro_expires_at = (start_at + timedelta(days=duration_days)).isoformat()

            db.execute(
                """
                UPDATE activation_codes
                SET is_used = 1, used_by_user_id = ?, used_at = ?
                WHERE id = ?
                """,
                (user.id, now_text, row["id"]),
            )
            db.execute(
                "UPDATE users SET plan = 'pro', pro_expires_at = ?, last_active_at = ? WHERE id = ?",
                (pro_expires_at, now_text, user.id),
            )
            updated = db.execute("SELECT * FROM users WHERE id = ?", (user.id,)).fetchone()
        return self.row_to_user(updated)

    def create_activation_code(
        self,
        code: str,
        plan: str = "pro",
        duration_days: int = 30,
        expires_at: str | None = None,
    ) -> None:
        with self.connect() as db:
            db.execute(
                """
                INSERT OR IGNORE INTO activation_codes
                (id, code_hash, plan, duration_days, expires_at, created_at, is_used)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """,
                (
                    str(uuid.uuid4()),
                    self.hash_secret(code.upper()),
                    plan,
                    int(duration_days),
                    expires_at,
                    iso_now(),
                ),
            )

    def row_to_user(self, row: sqlite3.Row) -> UserRecord:
        return UserRecord(
            id=row["id"],
            device_id=row["device_id"],
            plan=row["plan"],
            pro_expires_at=row["pro_expires_at"],
            created_at=row["created_at"],
            last_active_at=row["last_active_at"],
        )
