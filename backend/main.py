import asyncio
import hashlib
import json
import logging
import os
import threading
import time
import warnings
from io import BytesIO
from math import ceil
from typing import Any

from fastapi import FastAPI, File, Header, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field

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
DEFAULT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024
DEFAULT_MAX_IMAGE_PIXELS = 20_000_000


def positive_int_env(name: str, default: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)).strip())
    except (TypeError, ValueError):
        value = default
    return max(1, min(value, maximum))


MAX_UPLOAD_BYTES = positive_int_env("JI_MAX_UPLOAD_BYTES", DEFAULT_MAX_UPLOAD_BYTES, 25 * 1024 * 1024)
MAX_IMAGE_PIXELS = positive_int_env("JI_MAX_IMAGE_PIXELS", DEFAULT_MAX_IMAGE_PIXELS, 40_000_000)
IMAGE_VALIDATION_TIMEOUT_SECONDS = positive_int_env("JI_IMAGE_VALIDATION_TIMEOUT_SECONDS", 10, 30)
IMAGE_PROCESS_TIMEOUT_SECONDS = positive_int_env("JI_IMAGE_PROCESS_TIMEOUT_SECONDS", 90, 180)


class RequestBodyLimitMiddleware:
    def __init__(self, app, max_bytes: int, concurrency: int = 4) -> None:
        self.app = app
        self.max_bytes = max_bytes
        self.semaphore = asyncio.Semaphore(concurrency)

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http" or scope.get("path") != "/parse-image-task":
            await self.app(scope, receive, send)
            return

        content_length = next(
            (value.decode("latin-1") for key, value in scope.get("headers", []) if key.lower() == b"content-length"),
            None,
        )
        if content_length is not None:
            try:
                if int(content_length) > self.max_bytes:
                    await self._too_large(scope, send)
                    return
            except ValueError:
                response = JSONResponse(
                    status_code=400,
                    content={"ok": False, "code": "INVALID_CONTENT_LENGTH", "message": "Invalid request size."},
                )
                await response(scope, receive, send)
                return

        async with self.semaphore:
            messages = []
            total = 0
            while True:
                message = await receive()
                messages.append(message)
                if message["type"] == "http.request":
                    total += len(message.get("body", b""))
                    if total > self.max_bytes:
                        await self._too_large(scope, send)
                        return
                    if not message.get("more_body", False):
                        break
                elif message["type"] == "http.disconnect":
                    break

            async def replay_receive():
                if messages:
                    return messages.pop(0)
                return await receive()

            await self.app(scope, replay_receive, send)

    async def _too_large(self, scope, send) -> None:
        body = json.dumps(
            {"ok": False, "code": "UPLOAD_TOO_LARGE", "message": "Request exceeds the upload size limit."}
        ).encode("utf-8")
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode("ascii")),
                    (b"cache-control", b"no-store"),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})


_rate_limit_lock = threading.Lock()
_rate_limit_windows: dict[tuple[str, str], tuple[float, int]] = {}


def rate_limits_enabled() -> bool:
    if os.getenv("JI_ENVIRONMENT", "production").strip().lower() == "development":
        return os.getenv("JI_RATE_LIMIT_ENABLED", "true").strip().lower() not in FALSE_VALUES
    return True


def enforce_rate_limit(bucket: str, identity: str, limit: int, window_seconds: int) -> None:
    if not rate_limits_enabled():
        return

    now = time.monotonic()
    key = (bucket, identity)
    with _rate_limit_lock:
        current = _rate_limit_windows.get(key)
        if current is None or now - current[0] >= window_seconds:
            _rate_limit_windows[key] = (now, 1)
            retry_after = 0
        elif current[1] >= limit:
            retry_after = max(1, ceil(window_seconds - (now - current[0])))
        else:
            _rate_limit_windows[key] = (current[0], current[1] + 1)
            retry_after = 0

        if len(_rate_limit_windows) > 10_000:
            expired = [k for k, (started, _) in _rate_limit_windows.items() if now - started >= 600]
            for expired_key in expired:
                _rate_limit_windows.pop(expired_key, None)

    if retry_after:
        raise HTTPException(
            status_code=429,
            detail={"code": "RATE_LIMITED", "message": "Too many requests. Try again later."},
            headers={"Retry-After": str(retry_after)},
        )


def request_identity(request: Request, authorization: str | None = None) -> str:
    client_ip = request.client.host if request.client else "unknown"
    token = bearer_token(authorization)
    if token:
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        return f"{client_ip}:{token_hash}"
    return client_ip


def validate_image_bytes(image_bytes: bytes) -> str:
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(image_bytes)) as image:
                width, height = image.size
                if width <= 0 or height <= 0 or width * height > MAX_IMAGE_PIXELS:
                    raise ValueError("IMAGE_DIMENSIONS_TOO_LARGE")
                image_format = image.format
                image.verify()
    except ValueError:
        raise
    except (Image.DecompressionBombError, Image.DecompressionBombWarning, UnidentifiedImageError, OSError) as exc:
        raise ValueError("INVALID_IMAGE") from exc

    return Image.MIME.get(image_format or "", "application/octet-stream")

IS_DEVELOPMENT = os.getenv("JI_ENVIRONMENT", "production").strip().lower() == "development"
app = FastAPI(
    title="Ji AI Image Parser Backend",
    version="0.3.0",
    docs_url="/docs" if IS_DEVELOPMENT else None,
    redoc_url="/redoc" if IS_DEVELOPMENT else None,
    openapi_url="/openapi.json" if IS_DEVELOPMENT else None,
)
auth_store = AuthStore()
logger = logging.getLogger(__name__)
app.add_middleware(
    RequestBodyLimitMiddleware,
    max_bytes=MAX_UPLOAD_BYTES + 1024 * 1024,
    concurrency=4,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        origin.strip()
        for origin in os.getenv("JI_CORS_ALLOWED_ORIGINS", "https://primarytask.top").split(",")
        if origin.strip()
    ],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type"],
)


class AnonymousAuthRequest(BaseModel):
    device_id: str | None = Field(default=None, max_length=128)
    deviceId: str | None = Field(default=None, max_length=128)


class RedeemCodeRequest(BaseModel):
    code: str = Field(min_length=1, max_length=64)


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
    request: Request,
    authorization: str | None = Header(default=None),
) -> dict:
    enforce_rate_limit("anonymous", request_identity(request), limit=30, window_seconds=60)
    # Older clients may still send device_id/deviceId. These values are
    # untrusted and deliberately ignored. A valid bearer token preserves the
    # existing anonymous account; without one, create a separate account.
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
def redeem_code(
    payload: RedeemCodeRequest,
    request: Request,
    authorization: str | None = Header(default=None),
) -> dict:
    enforce_rate_limit("redeem-ip", request_identity(request), limit=120, window_seconds=60)
    user = require_user(authorization)
    enforce_rate_limit("redeem", request_identity(request, authorization), limit=10, window_seconds=60)
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
    request: Request,
    image: UploadFile = File(...),
    authorization: str | None = Header(default=None),
) -> dict:
    request_started_at = time.perf_counter()
    enforce_rate_limit("parse-ip", request_identity(request), limit=120, window_seconds=60)
    enforce_rate_limit("parse", request_identity(request, authorization), limit=30, window_seconds=60)
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

    receive_started_at = time.perf_counter()
    image_bytes = await image.read(MAX_UPLOAD_BYTES + 1)
    if len(image_bytes) > MAX_UPLOAD_BYTES:
        return JSONResponse(
            status_code=413,
            content={"ok": False, "code": "UPLOAD_TOO_LARGE", "message": "Image exceeds the 10 MiB upload limit."},
        )

    try:
        content_type = await asyncio.wait_for(
            asyncio.to_thread(validate_image_bytes, image_bytes),
            timeout=IMAGE_VALIDATION_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        return JSONResponse(
            status_code=408,
            content={"ok": False, "code": "IMAGE_VALIDATION_TIMEOUT", "message": "Image validation timed out."},
        )
    except ValueError as exc:
        code = str(exc)
        status_code = 413 if code == "IMAGE_DIMENSIONS_TOO_LARGE" else 400
        message = "Image dimensions exceed the pixel limit." if status_code == 413 else "The uploaded file is not a supported image."
        return JSONResponse(status_code=status_code, content={"ok": False, "code": code, "message": message})

    logger.info("image_receive_metrics receiveMs=%s", int((time.perf_counter() - receive_started_at) * 1000))
    plan = user.effective_plan if user else None
    model_name = model_name_for_plan(plan) if user else vision_model_name()

    try:
        model_started_at = time.perf_counter()
        result = await asyncio.wait_for(
            parse_image_with_model(
                image_bytes=image_bytes,
                content_type=content_type,
                model_name=model_name,
                raise_on_error=True,
            ),
            timeout=IMAGE_PROCESS_TIMEOUT_SECONDS,
        )
        logger.info(
            "image_total_metrics model=%s modelMs=%s totalMs=%s tasks=%s",
            model_name,
            int((time.perf_counter() - model_started_at) * 1000),
            int((time.perf_counter() - request_started_at) * 1000),
            len(result.get("tasks", [])),
        )
    except asyncio.TimeoutError:
        return JSONResponse(
            status_code=504,
            content={"ok": False, "code": "IMAGE_PROCESSING_TIMEOUT", "message": "Image processing timed out. Please try again."},
        )
    except Exception:
        # 识别失败不扣次数，也不泄露模型细节给 App。
        return {"tasks": [], "message": "识别失败，请重试或手动添加任务"}

    all_tasks = result.get("tasks", [])
    if user:
        auth_store.increment_usage(user.id, model_name)
        latest_user = auth_store.get_user_by_token(bearer_token(authorization)) or user
        result["usage"] = auth_store.usage_payload(latest_user)
        pro_hint = build_pro_hint(all_tasks, latest_user)
        if pro_hint:
            result["proHint"] = pro_hint
        result["tasks"] = limit_tasks_for_plan(all_tasks, latest_user.effective_plan)
    else:
        result["tasks"] = limit_tasks_for_plan(all_tasks, None)

    return result


def limit_tasks_for_plan(tasks: list[dict[str, Any]], plan: str | None) -> list[dict[str, Any]]:
    return tasks[:20] if plan == "pro" else tasks[:3]


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
