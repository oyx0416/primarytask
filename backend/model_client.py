import base64
import logging
import os
import time
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
    if "complex" in stage:
        return _float_env("JI_AI_COMPLEX_IMAGE_TIMEOUT_SECONDS", 20.0)
    return _float_env("JI_AI_SINGLE_IMAGE_TIMEOUT_SECONDS", 12.0)


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

    started_at = time.perf_counter()
    try:
        model_response = await call_openai_compatible_vision_model(
            image_bytes=image_bytes,
            content_type=content_type,
            model_name=model_name or vision_model_name(),
        )
        rules_started_at = time.perf_counter()
        result = normalize_task_response(model_response)
        logger.info(
            "image_rules_metrics model=%s rulesMs=%s totalMs=%s tasks=%s",
            model_name or vision_model_name(),
            int((time.perf_counter() - rules_started_at) * 1000),
            int((time.perf_counter() - started_at) * 1000),
            len(result.get("tasks", [])),
        )
        return result
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
    image_bytes, content_type, complex_image = prepare_image_for_model(image_bytes, content_type)
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
        stage="complex-vision" if complex_image else "single-vision",
        max_tokens=max_tokens_for_image(complex_image),
    )
    return extract_message_content(response_json)


def prepare_image_for_model(image_bytes: bytes, content_type: str) -> tuple[bytes, str, bool]:
    if os.getenv("JI_AI_COMPRESS_IMAGE", "true").strip().lower() in FALSE_VALUES:
        return image_bytes, content_type, is_large_image_payload(image_bytes)

    try:
        from PIL import Image, ImageOps
    except Exception:
        logger.warning("Pillow is not installed; sending original image to model")
        return image_bytes, content_type, is_large_image_payload(image_bytes)

    started_at = time.perf_counter()
    try:
        with Image.open(BytesIO(image_bytes)) as image:
            image = ImageOps.exif_transpose(image)
            original_size = image.size
            original_long_edge = max(original_size)
            original_short_edge = max(1, min(original_size))
            complex_image = original_long_edge >= 2200 or (original_long_edge / original_short_edge) >= 2.2
            default_max_edge = 1440 if complex_image else 1280
            default_quality = 85 if complex_image else 82
            max_edge = min(max(900, _int_env("JI_AI_IMAGE_MAX_EDGE", default_max_edge)), 2048)
            quality = min(max(40, _int_env("JI_AI_IMAGE_JPEG_QUALITY", default_quality)), 95)
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
                "image_prepare_metrics compressMs=%s",
                int((time.perf_counter() - started_at) * 1000),
            )
            return compressed, "image/jpeg", complex_image
        logger.info(
            "image_prepare_metrics compressMs=%s",
            int((time.perf_counter() - started_at) * 1000),
        )
        return image_bytes, content_type, complex_image
    except Exception:
        logger.exception("Failed to compress image; sending original image")

    return image_bytes, content_type, is_large_image_payload(image_bytes)


def is_large_image_payload(image_bytes: bytes) -> bool:
    return len(image_bytes) >= 1_200_000


def max_tokens_for_image(complex_image: bool) -> int:
    configured = os.getenv("JI_AI_MAX_OUTPUT_TOKENS", "").strip()
    if configured:
        try:
            return max(300, min(int(configured), 2000))
        except ValueError:
            pass
    return 1200 if complex_image else 500


async def post_chat_completion(
    *,
    model: str,
    messages: list[dict[str, Any]],
    temperature: float,
    stage: str,
    max_tokens: int,
) -> dict[str, Any]:
    api_key = os.getenv("JI_AI_MODEL_API_KEY", "").strip()
    timeout_seconds = stage_timeout_seconds(stage)
    endpoint = model_endpoint()

    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
    }

    payload["max_tokens"] = max_tokens

    if os.getenv("JI_AI_DISABLE_THINKING", "true").strip().lower() not in FALSE_VALUES:
        payload["enable_thinking"] = False

    if os.getenv("JI_AI_RESPONSE_FORMAT_JSON", "true").strip().lower() not in FALSE_VALUES:
        payload["response_format"] = {"type": "json_object"}

    last_error: Exception | None = None
    for attempt in range(model_retry_count() + 1):
        try:
            async with httpx.AsyncClient(timeout=timeout_seconds) as client:
                started_at = time.perf_counter()
                response = await client.post(
                    endpoint,
                    headers={
                        "Authorization": f"Bearer {api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
                logger.info(
                    "%s model_request_metrics model=%s status=%s requestMs=%s",
                    stage,
                    model,
                    response.status_code,
                    int((time.perf_counter() - started_at) * 1000),
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
                "%s model request returned %s",
                stage,
                exc.response.status_code,
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
