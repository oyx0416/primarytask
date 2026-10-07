from pathlib import Path


BACKEND = Path(__file__).resolve().parents[1] / "backend"


AUTH_STORE = r'''import hashlib
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
        # Never use a client-provided identifier as account authentication.
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
'''


MAIN = r'''import os
from typing import Any

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from auth_store import AuthStore, UserRecord
from model_client import (
    is_mock_mode,
    model_name_for_plan,
    parse_image_with_model,
    text_model_name,
    use_two_stage_parser,
    vision_model_name,
)


FALSE_VALUES = {"0", "false", "no", "off"}

app = FastAPI(title="Ji AI Image Parser Backend", version="0.3.0")
auth_store = AuthStore()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


class AnonymousAuthRequest(BaseModel):
    device_id: str | None = None
    deviceId: str | None = None


class RedeemCodeRequest(BaseModel):
    code: str


def auth_required() -> bool:
    if os.getenv("JI_ENVIRONMENT", "production").strip().lower() != "development":
        return True
    return os.getenv("JI_AUTH_REQUIRED", "true").strip().lower() not in FALSE_VALUES


def bearer_token(authorization: str | None) -> str:
    if not authorization:
        return ""
    prefix = "Bearer "
    if authorization.startswith(prefix):
        return authorization[len(prefix) :].strip()
    return ""


def optional_user(authorization: str | None) -> UserRecord | None:
    token = bearer_token(authorization)
    if not token:
        return None
    return auth_store.get_user_by_token(token)


def require_user(authorization: str | None) -> UserRecord:
    user = optional_user(authorization)
    if user is None:
        raise HTTPException(status_code=401, detail="无效或缺失 token")
    return user


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "mock": is_mock_mode(),
        "authRequired": auth_required(),
        "twoStage": use_two_stage_parser(),
        "visionModel": vision_model_name(),
        "freeModel": model_name_for_plan("free"),
        "proModel": model_name_for_plan("pro"),
        "textModel": text_model_name(),
    }


@app.post("/auth/anonymous")
def auth_anonymous(
    payload: AnonymousAuthRequest,
    authorization: str | None = Header(default=None),
) -> dict:
    # Legacy device_id/deviceId fields are accepted but ignored.
    existing_user = optional_user(authorization)
    if existing_user is not None:
        return auth_store.user_payload(existing_user, token=bearer_token(authorization))
    user, token = auth_store.anonymous_session()
    return auth_store.user_payload(user, token=token)


@app.get("/me")
def me(authorization: str | None = Header(default=None)) -> dict:
    user = require_user(authorization)
    return auth_store.user_payload(user)


@app.post("/redeem-code")
def redeem_code(payload: RedeemCodeRequest, authorization: str | None = Header(default=None)) -> dict:
    user = require_user(authorization)
    try:
        updated_user = auth_store.redeem_code(user, payload.code)
    except ValueError as exc:
        return JSONResponse(
            status_code=400,
            content={"ok": False, "code": "INVALID_CODE", "message": str(exc), "usage": auth_store.usage_payload(user)},
        )
    response = auth_store.user_payload(updated_user)
    response["ok"] = True
    return response


@app.post("/parse-image-task")
async def parse_image_task(
    image: UploadFile = File(...),
    authorization: str | None = Header(default=None),
) -> dict:
    user = optional_user(authorization)
    if user is None and auth_required():
        return JSONResponse(
            status_code=401,
            content={"ok": False, "code": "UNAUTHORIZED", "message": "请先初始化账号"},
        )

    usage_before = auth_store.usage_payload(user) if user else None
    if user and usage_before["usedToday"] >= usage_before["dailyQuota"]:
        return JSONResponse(
            status_code=429,
            content={
                "ok": False,
                "code": "QUOTA_EXCEEDED",
                "message": "今日免费识别次数已用完，升级 Pro 可继续使用",
                "usage": usage_before,
            },
        )

    image_bytes = await image.read()
    plan = user.effective_plan if user else None
    model_name = model_name_for_plan(plan) if user else vision_model_name()

    try:
        result = await parse_image_with_model(
            image_bytes=image_bytes,
            content_type=image.content_type or "image/jpeg",
            model_name=model_name,
            raise_on_error=True,
        )
    except Exception:
        # 识别失败不扣次数，也不泄露模型细节给 App。
        return {"tasks": [], "message": "识别失败，请重试或手动添加任务"}

    if user:
        auth_store.increment_usage(user.id, model_name)
        latest_user = auth_store.get_user_by_token(bearer_token(authorization)) or user
        result["usage"] = auth_store.usage_payload(latest_user)
        pro_hint = build_pro_hint(result.get("tasks", []), latest_user)
        if pro_hint:
            result["proHint"] = pro_hint

    return result


def build_pro_hint(tasks: list[dict[str, Any]], user: UserRecord) -> dict | None:
    if user.effective_plan == "pro":
        return None

    requires_pro = len(tasks) > 3
    advanced = [task for task in tasks if is_advanced_task(task)]
    if advanced:
        requires_pro = True

    if not requires_pro:
        return None

    reason = "已识别到高级场景或多个任务，升级 Pro 可一键保存全部"
    if advanced:
        reason = "已识别到生活/其他/面试/缴费/购物等高级场景，升级 Pro 后可保存"
    elif len(tasks) > 3:
        reason = f"已识别到 {len(tasks)} 个任务，Free 可保存前 3 个，升级 Pro 可一键保存全部"

    return {
        "requiresPro": True,
        "reason": reason,
        "freeSaveLimit": 3,
    }


def is_advanced_task(task: dict[str, Any]) -> bool:
    text = " ".join(str(value or "") for value in task.values())
    category = str(task.get("category") or task.get("cat") or "")
    sub_type = str(task.get("subType") or task.get("type") or "")

    if category in {"生活", "其他", "life", "daily", "other"}:
        return True
    if sub_type in {"面试", "缴费", "购物", "快递", "家人交代", "interview", "payment", "shopping"}:
        return True
    advanced_words = ("面试", "缴费", "购物", "证件", "办理", "花呗", "水电费", "取件", "快递")
    return any(word in text for word in advanced_words)
'''


MODEL_CLIENT = r'''import base64
import logging
import os
from io import BytesIO
from pathlib import Path
from typing import Any

import httpx
from dotenv import load_dotenv

from prompt import SYSTEM_PROMPT, USER_PROMPT
from schemas import empty_task_response, mock_task_response, normalize_task_response

load_dotenv(Path(__file__).with_name(".env"))

logger = logging.getLogger(__name__)

FALSE_VALUES = {"0", "false", "no", "off"}
TRUE_VALUES = {"1", "true", "yes", "on"}
TRANSIENT_HTTP_ERRORS = (
    httpx.ConnectError,
    httpx.ReadError,
    httpx.ReadTimeout,
    httpx.RemoteProtocolError,
    httpx.TransportError,
)


def is_mock_mode() -> bool:
    explicit_mock = os.getenv("JI_AI_MOCK", "").strip().lower()
    if explicit_mock in TRUE_VALUES:
        return True
    if explicit_mock in FALSE_VALUES:
        return False
    return not bool(os.getenv("JI_AI_MODEL_API_KEY", "").strip())


def use_two_stage_parser() -> bool:
    return os.getenv("JI_AI_USE_TWO_STAGE", "false").strip().lower() in TRUE_VALUES


def model_name_for_plan(plan: str | None) -> str:
    legacy = os.getenv("JI_AI_MODEL_NAME", "").strip()
    if plan == "pro":
        return os.getenv("JI_AI_PRO_MODEL_NAME", "").strip() or legacy or "qwen-vl-max"
    if plan == "free":
        return os.getenv("JI_AI_FREE_MODEL_NAME", "").strip() or legacy or "qwen-vl-plus"
    return legacy or os.getenv("JI_AI_FREE_MODEL_NAME", "").strip() or "qwen-vl-plus"


def vision_model_name() -> str:
    return model_name_for_plan(None)


def text_model_name() -> str:
    return os.getenv("JI_AI_TEXT_MODEL_NAME", "").strip() or vision_model_name()


def model_timeout_seconds() -> float:
    return _float_env("JI_AI_MODEL_TIMEOUT_SECONDS", _float_env("JI_AI_MODEL_TIMEOUT", 60.0))


def stage_timeout_seconds(stage: str) -> float:
    return model_timeout_seconds()


def model_retry_count() -> int:
    return max(0, _int_env("JI_AI_MODEL_RETRY_COUNT", _int_env("JI_AI_MODEL_RETRIES", 0)))


async def parse_image_with_model(
    image_bytes: bytes,
    content_type: str = "image/jpeg",
    model_name: str | None = None,
    raise_on_error: bool = False,
) -> dict:
    if is_mock_mode():
        return mock_task_response()

    try:
        model_response = await call_openai_compatible_vision_model(
            image_bytes=image_bytes,
            content_type=content_type,
            model_name=model_name or vision_model_name(),
        )
        return normalize_task_response(model_response)
    except Exception:
        logger.exception("Failed to parse image with vision model")
        if raise_on_error:
            raise
        return empty_task_response()


async def call_openai_compatible_vision_model(
    image_bytes: bytes,
    content_type: str,
    model_name: str,
) -> Any:
    image_bytes, content_type = prepare_image_for_model(image_bytes, content_type)
    image_base64 = base64.b64encode(image_bytes).decode("ascii")
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": USER_PROMPT},
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:{content_type};base64,{image_base64}"},
                },
            ],
        },
    ]
    response_json = await post_chat_completion(
        model=model_name,
        messages=messages,
        temperature=0.01,
        stage="single-vision",
    )
    return extract_message_content(response_json)


def prepare_image_for_model(image_bytes: bytes, content_type: str) -> tuple[bytes, str]:
    if os.getenv("JI_AI_COMPRESS_IMAGE", "true").strip().lower() in FALSE_VALUES:
        return image_bytes, content_type

    try:
        from PIL import Image, ImageOps
    except Exception:
        logger.warning("Pillow is not installed; sending original image to model")
        return image_bytes, content_type

    try:
        max_edge = min(max(900, _int_env("JI_AI_IMAGE_MAX_EDGE", 1600)), 2048)
        quality = min(max(40, _int_env("JI_AI_IMAGE_JPEG_QUALITY", 90)), 95)
        with Image.open(BytesIO(image_bytes)) as image:
            image = ImageOps.exif_transpose(image)
            original_size = image.size
            original_long_edge = max(original_size)
            image.thumbnail((max_edge, max_edge), Image.Resampling.LANCZOS)

            if image.mode in {"RGBA", "LA"}:
                background = Image.new("RGB", image.size, "white")
                alpha = image.getchannel("A")
                background.paste(image.convert("RGB"), mask=alpha)
                image = background
            elif image.mode != "RGB":
                image = image.convert("RGB")

            output = BytesIO()
            image.save(output, format="JPEG", quality=quality, optimize=True)
            compressed = output.getvalue()

        if compressed and (
            original_long_edge > max_edge
            or content_type.lower() not in {"image/jpeg", "image/jpg"}
            or len(compressed) < len(image_bytes)
        ):
            logger.info(
                "Prepared image for model: %sx%s -> %sx%s, %s -> %s bytes",
                original_size[0],
                original_size[1],
                image.size[0],
                image.size[1],
                len(image_bytes),
                len(compressed),
            )
            return compressed, "image/jpeg"
    except Exception:
        logger.exception("Failed to compress image; sending original image")

    return image_bytes, content_type


async def post_chat_completion(
    *,
    model: str,
    messages: list[dict[str, Any]],
    temperature: float,
    stage: str,
) -> dict[str, Any]:
    api_key = os.getenv("JI_AI_MODEL_API_KEY", "").strip()
    timeout_seconds = stage_timeout_seconds(stage)
    endpoint = model_endpoint()

    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
    }

    max_tokens = os.getenv("JI_AI_MAX_OUTPUT_TOKENS", "1200").strip()
    if max_tokens:
        payload["max_tokens"] = int(max_tokens)

    if os.getenv("JI_AI_DISABLE_THINKING", "true").strip().lower() not in FALSE_VALUES:
        payload["enable_thinking"] = False

    if os.getenv("JI_AI_RESPONSE_FORMAT_JSON", "true").strip().lower() not in FALSE_VALUES:
        payload["response_format"] = {"type": "json_object"}

    last_error: Exception | None = None
    for attempt in range(model_retry_count() + 1):
        try:
            async with httpx.AsyncClient(timeout=timeout_seconds) as client:
                response = await client.post(
                    endpoint,
                    headers={
                        "Authorization": f"Bearer {api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
                response.raise_for_status()
                return response.json()
        except TRANSIENT_HTTP_ERRORS as exc:
            last_error = exc
            logger.warning("%s model request failed on attempt %s: %r", stage, attempt + 1, exc)
            if attempt >= model_retry_count():
                raise
        except httpx.HTTPStatusError as exc:
            logger.error(
                "%s model request returned %s: %s",
                stage,
                exc.response.status_code,
                exc.response.text[:500],
            )
            raise

    if last_error:
        raise last_error
    raise RuntimeError(f"{stage} model request failed")


def model_endpoint() -> str:
    configured_endpoint = os.getenv("JI_AI_MODEL_ENDPOINT", "").strip()
    if configured_endpoint:
        return configured_endpoint

    base_url = os.getenv("JI_AI_MODEL_BASE_URL", "").strip().rstrip("/")
    if not base_url:
        raise RuntimeError("JI_AI_MODEL_BASE_URL or JI_AI_MODEL_ENDPOINT is required")

    if base_url.endswith("/chat/completions"):
        return base_url

    return f"{base_url}/chat/completions"


def extract_message_content(response_json: dict[str, Any]) -> str:
    choices = response_json.get("choices")
    if not choices:
        raise ValueError("Model response has no choices")

    message = choices[0].get("message", {})
    content = message.get("content", "")

    if isinstance(content, str):
        return content

    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text") or item.get("content")
                if text:
                    parts.append(str(text))
        return "\n".join(parts)

    return str(content)


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)).strip())
    except (TypeError, ValueError):
        return default


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)).strip())
    except (TypeError, ValueError):
        return default
'''


SCHEMAS = r'''import json
from typing import Any

from pydantic import BaseModel, Field


class ParsedTask(BaseModel):
    title: str = ""
    category: str = ""
    subType: str = ""
    importance: str = ""
    contextLabel: str = ""
    contextValue: str = ""
    time: str = ""
    location: str = ""
    platform: str = ""
    materials: str = ""
    note: str = ""
    status: str = "未完成"
    rawText: str = ""


class ParsedTaskResponse(BaseModel):
    tasks: list[ParsedTask] = Field(default_factory=list)


FINISHED_WORDS = ("已完成", "已批阅", "得分", "查看答案", "历史记录", "已结束", "已提交", "已评价", "已关闭")
UNFINISHED_WORDS = ("未完成", "未做", "进行中", "待完成", "未提交", "待提交", "前往作业")
STUDY_WORDS = ("作业", "题库作业", "手动出题", "前往作业", "课程", "课件", "章节测试", "测试", "考试", "成绩", "报告", "论文", "学习通", "智慧职教", "班级学习")
WORK_WORDS = ("面试", "实习", "简历", "会议", "项目", "材料提交", "入职", "培训", "申请", "HR", "公司", "岗位")
LIFE_WORDS = ("快递", "缴费", "水电费", "取件", "购物", "出行", "健康", "运动", "生活提醒", "预约", "还款", "花呗")
HOMEWORK_WORDS = ("作业", "题库作业", "手动出题", "前往作业", "未做", "作业时间", "课程任务")
EXAM_WORDS = ("考试", "测验", "期末", "期中", "补考", "考核", "测试")
INTERVIEW_WORDS = ("面试", "笔试", "群面", "终面", "HR", "机务面试", "岗位面试")
PAYMENT_WORDS = ("缴费", "支付", "付款", "费用", "报名费", "水电费", "欠费", "还款")
MEETING_WORDS = ("会议", "开会", "汇报", "讨论", "例会", "项目会")
HIGH_WORDS = ("高", "重要", "紧急", "必须", "考试", "面试", "报名截止", "缴费截止", "材料提交", "入职", "实习", "high", "urgent", "critical")


def mock_task_response() -> dict:
    return ParsedTaskResponse(
        tasks=[
            ParsedTask(
                title="测试任务标题",
                category="学习",
                subType="作业",
                importance="普通",
                contextLabel="课程",
                contextValue="测试课程",
                time="2026-06-30 23:59",
                platform="测试平台",
                note="这是后端模拟返回",
            )
        ]
    ).model_dump()


def empty_task_response() -> dict:
    return ParsedTaskResponse(tasks=[]).model_dump()


def normalize_task_response(data: Any) -> dict:
    if isinstance(data, str):
        data = json.loads(extract_json_object(data))

    if isinstance(data, list):
        raw_tasks = data
    elif isinstance(data, dict):
        raw_tasks = data.get("tasks")
        if raw_tasks is None and any(key in data for key in ParsedTask.model_fields.keys()):
            raw_tasks = [data]
    else:
        return empty_task_response()

    if not isinstance(raw_tasks, list):
        return empty_task_response()

    tasks: list[ParsedTask] = []
    for raw in raw_tasks:
        if not isinstance(raw, dict):
            continue

        title = clean_text(raw.get("title", raw.get("t", "")))
        if not title:
            continue

        text = " ".join(clean_text(value) for value in raw.values())
        if looks_finished(text):
            continue

        sub_type = normalize_type(clean_text(raw.get("subType", raw.get("type", ""))), f"{title} {text}")
        category = normalize_category(clean_text(raw.get("category", raw.get("cat", ""))), f"{title} {sub_type} {text}")
        context_value = clean_text(raw.get("contextValue", raw.get("course", "")))
        context_label = clean_text(raw.get("contextLabel", "")) or default_context_label(category, sub_type, context_value)

        tasks.append(
            ParsedTask(
                title=title,
                category=category,
                subType=sub_type,
                importance=normalize_importance(clean_text(raw.get("importance", raw.get("imp", ""))), f"{title} {text}"),
                contextLabel=context_label,
                contextValue=context_value,
                time=clean_text(raw.get("time", raw.get("ddl", ""))),
                location=clean_text(raw.get("location", raw.get("loc", ""))),
                platform=clean_text(raw.get("platform", raw.get("src", ""))),
                materials=clean_text(raw.get("materials", "")),
                note=clean_note(clean_text(raw.get("note", ""))),
                status=normalize_status(clean_text(raw.get("status", ""))),
            )
        )

    if not tasks:
        return empty_task_response()
    if looks_like_independent_homework_list(tasks):
        return ParsedTaskResponse(tasks=tasks[:20]).model_dump()
    if len(tasks) > 1 and looks_like_single_notice(tasks):
        return ParsedTaskResponse(tasks=[merge_notice_tasks(tasks)]).model_dump()
    return ParsedTaskResponse(tasks=tasks[:20]).model_dump()


def clean_text(value: Any) -> str:
    return str(value or "").strip()


def contains_any(text: str, words: tuple[str, ...]) -> bool:
    lower_text = text.lower()
    return any(word.lower() in lower_text for word in words)


def normalize_category(category: str, text: str) -> str:
    lower = category.strip().lower()
    if lower == "study" or category == "学习":
        return "学习"
    if lower == "work" or category == "工作":
        return "工作"
    if lower in {"life", "daily"} or category in {"生活", "日常"}:
        return "生活"
    if lower == "other" or category in {"其他", "其它"}:
        return "其他"
    if contains_any(text, STUDY_WORDS):
        return "学习"
    if contains_any(text, WORK_WORDS):
        return "工作"
    if contains_any(text, LIFE_WORDS):
        return "生活"
    return "其他"


def normalize_type(sub_type: str, text: str) -> str:
    lower = sub_type.strip().lower()
    if contains_any(text, HOMEWORK_WORDS) or lower == "homework":
        return "作业"
    if lower in {"exam", "test"} or contains_any(text, EXAM_WORDS):
        return "考试"
    if lower == "interview" or contains_any(text, INTERVIEW_WORDS):
        return "面试"
    if lower == "payment" or contains_any(text, PAYMENT_WORDS):
        return "缴费"
    if lower == "meeting" or contains_any(text, MEETING_WORDS):
        return "会议"
    if lower == "shopping":
        return "购物"
    return "提醒"


def normalize_importance(importance: str, text: str) -> str:
    lower = importance.strip().lower()
    if lower == "high" or contains_any(f"{importance} {text}", HIGH_WORDS):
        return "高"
    if lower in {"low", "normal"}:
        return "普通"
    return "普通"


def normalize_status(status: str) -> str:
    if contains_any(status, FINISHED_WORDS):
        return "已完成"
    return "未完成"


def default_context_label(category: str, sub_type: str, value: str) -> str:
    if not value:
        return ""
    if category == "学习":
        return "课程"
    if category == "工作":
        return "公司/岗位" if sub_type == "面试" else "项目"
    if category == "生活":
        return "缴费项目" if sub_type == "缴费" else "事项"
    return ""


def looks_finished(text: str) -> bool:
    return contains_any(text, FINISHED_WORDS) and not contains_any(text, UNFINISHED_WORDS)


def clean_note(note: str) -> str:
    if not note:
        return ""
    return note.strip()[:220]


def looks_like_independent_homework_list(tasks: list[ParsedTask]) -> bool:
    titles = {task.title.replace(" ", "") for task in tasks if task.subType == "作业"}
    if len(titles) < 2:
        return False
    numbered = [title for title in titles if "作业" in title and any(ch.isdigit() for ch in title)]
    return len(numbered) >= 2


def looks_like_single_notice(tasks: list[ParsedTask]) -> bool:
    text = " ".join(task.title + " " + task.subType + " " + task.note for task in tasks)
    if looks_like_independent_homework_list(tasks):
        return False
    return contains_any(text, ("面试", "会议", "报名", "通知", "入职")) and len({task.time for task in tasks if task.time}) <= 3


def merge_notice_tasks(tasks: list[ParsedTask]) -> ParsedTask:
    first = tasks[0]
    text = "\n".join(part for task in tasks for part in (task.title, task.note, task.location, task.materials) if part)
    title = next((task.title for task in tasks if any(word in task.title for word in ("面试", "会议", "报名", "通知", "入职"))), first.title)
    sub_type = next((task.subType for task in tasks if task.subType in {"面试", "会议"}), first.subType)
    return first.model_copy(
        update={
            "title": title or "待处理通知",
            "subType": sub_type,
            "importance": "高",
            "time": next((task.time for task in tasks if task.time), ""),
            "location": "；".join(dict.fromkeys(task.location for task in tasks if task.location))[:80],
            "materials": "；".join(dict.fromkeys(task.materials for task in tasks if task.materials))[:120],
            "note": "\n".join(dict.fromkeys(line.strip() for line in text.splitlines() if line.strip() and line.strip() != title))[:500],
            "status": "未完成",
        }
    )


def extract_json_object(text: str) -> str:
    clean = text.strip()
    if clean.startswith("```"):
        clean = clean.strip("`").strip()
        if clean.lower().startswith("json"):
            clean = clean[4:].strip()
    start = clean.find("{")
    end = clean.rfind("}")
    if start < 0 or end < start:
        raise ValueError("No JSON object found in model response")
    return clean[start : end + 1]
'''


REQUIREMENTS = """fastapi==0.115.6
uvicorn[standard]==0.34.0
python-multipart==0.0.20
httpx==0.28.1
python-dotenv==1.0.1
pillow==10.4.0
"""


def write_file(name: str, content: str) -> None:
    path = BACKEND / name
    path.write_text(content, encoding="utf-8", newline="\n")
    print(f"wrote {path}")


def main() -> None:
    if not BACKEND.exists():
        raise SystemExit(f"Backend directory not found: {BACKEND}")
    write_file("auth_store.py", AUTH_STORE)
    write_file("main.py", MAIN)
    write_file("model_client.py", MODEL_CLIENT)
    write_file("schemas.py", SCHEMAS)
    write_file("requirements.txt", REQUIREMENTS)


if __name__ == "__main__":
    main()
