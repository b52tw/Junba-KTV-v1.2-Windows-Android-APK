from __future__ import annotations

import json
import re
import time
from pathlib import Path
from typing import Callable, Iterable

from .models import Segment, TextTrack

DEFAULT_TEXT_MODEL = "gemini-3.8-flash"
FALLBACK_MODELS = ("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash")
RETRY_DELAYS = (2, 5, 10)


def _json_from_text(text: str):
    raw = (text or "").strip()
    raw = re.sub(r"^```(?:json)?\s*", "", raw, flags=re.I)
    raw = re.sub(r"\s*```$", "", raw)
    a, b = raw.find("["), raw.rfind("]")
    if a >= 0 and b > a:
        raw = raw[a : b + 1]
    return json.loads(raw)


def _client(api_key: str):
    if not api_key.strip():
        raise RuntimeError("請先輸入 Gemini API Key。")
    from google import genai

    return genai.Client(api_key=api_key.strip())


def _model_order(primary: str) -> list[str]:
    out: list[str] = []
    for m in (primary, *FALLBACK_MODELS):
        m = (m or "").strip()
        if m and m not in out:
            out.append(m)
    return out


def _error_text(exc: Exception) -> str:
    return f"{type(exc).__name__}: {exc}".strip()


def _retryable(exc: Exception) -> bool:
    s = _error_text(exc).upper()
    return any(
        token in s
        for token in (
            "429",
            "500",
            "502",
            "503",
            "504",
            "UNAVAILABLE",
            "RESOURCE_EXHAUSTED",
            "DEADLINE_EXCEEDED",
            "TIMED OUT",
            "TIMEOUT",
            "HIGH DEMAND",
            "SERVERERROR",
            "INTERNAL",
        )
    )


def _model_missing(exc: Exception) -> bool:
    s = _error_text(exc).upper()
    return any(token in s for token in ("404", "NOT_FOUND", "MODEL NOT FOUND", "MODEL_NOT_FOUND"))


def _friendly_error(exc: Exception) -> RuntimeError:
    s = _error_text(exc)
    u = s.upper()
    if "401" in u or "403" in u or "API KEY" in u or "PERMISSION_DENIED" in u:
        return RuntimeError("Gemini API Key 無效、權限不足或尚未啟用服務。請檢查 API Key。")
    if _retryable(exc):
        return RuntimeError("Gemini 目前高負載或暫時無法使用；原始文字與 KTV 不受影響，可稍後再試。")
    return RuntimeError(s)


def _generate_with_fallback(client, contents, primary_model: str, *, progress: Callable[[str], None] | None, label: str):
    last_exc: Exception | None = None
    models = _model_order(primary_model)
    for model_index, model in enumerate(models):
        for attempt, delay in enumerate(RETRY_DELAYS, start=1):
            try:
                if progress:
                    progress(f"{label}｜{model}｜嘗試 {attempt}/{len(RETRY_DELAYS)}")
                response = client.models.generate_content(model=model, contents=contents)
                return response, model
            except Exception as exc:
                last_exc = exc
                if _model_missing(exc):
                    if progress and model_index + 1 < len(models):
                        progress(f"{model} 暫不可用，切換備援模型…")
                    break
                if _retryable(exc):
                    if attempt < len(RETRY_DELAYS):
                        if progress:
                            progress(f"Gemini 忙碌中｜{model}｜{delay} 秒後自動重試 {attempt + 1}/{len(RETRY_DELAYS)}")
                        time.sleep(delay)
                        continue
                    if progress and model_index + 1 < len(models):
                        progress(f"{model} 仍忙碌，切換備援模型…")
                    break
                raise _friendly_error(exc) from exc
    if last_exc:
        raise _friendly_error(last_exc) from last_exc
    raise RuntimeError("Gemini 未回傳結果。")


def _upload_with_retry(client, audio_path: Path, progress: Callable[[str], None] | None):
    last_exc: Exception | None = None
    for attempt, delay in enumerate(RETRY_DELAYS, start=1):
        try:
            if progress:
                progress(f"上傳錄音至 Gemini｜嘗試 {attempt}/{len(RETRY_DELAYS)}")
            return client.files.upload(file=str(audio_path))
        except Exception as exc:
            last_exc = exc
            if _retryable(exc) and attempt < len(RETRY_DELAYS):
                if progress:
                    progress(f"Gemini 上傳服務忙碌｜{delay} 秒後自動重試…")
                time.sleep(delay)
                continue
            raise _friendly_error(exc) from exc
    if last_exc:
        raise _friendly_error(last_exc) from last_exc
    raise RuntimeError("Gemini 音訊上傳失敗。")


def polish_track(
    track: TextTrack,
    api_key: str,
    model: str = DEFAULT_TEXT_MODEL,
    style: str = "繁體台灣用語、保留原意、修正錯字與口語贅詞",
    progress: Callable[[str], None] | None = None,
) -> TextTrack:
    """只送文字到 Gemini，保留既有時間軸；失敗不會改動原文字軌。"""
    client = _client(api_key)
    segs: list[Segment] = []
    batch = 24
    used_models: list[str] = []
    for offset in range(0, len(track.segments), batch):
        part = track.segments[offset : offset + batch]
        if progress:
            progress(f"Gemini 順稿 {offset + 1}–{offset + len(part)} / {len(track.segments)}")
        payload = [{"id": i, "speaker": s.speaker, "text": s.text} for i, s in enumerate(part)]
        prompt = (
            "你是繁體中文會議逐字稿校稿助理。請只回傳 JSON 陣列，不要 markdown。\n"
            f"規則：{style}。不得新增原錄音沒有的事實；專有名詞不確定就保留原文；每個 id 必須保留。\n"
            "輸入：" + json.dumps(payload, ensure_ascii=False)
        )
        resp, used = _generate_with_fallback(client, prompt, model, progress=progress, label="Gemini 順稿")
        used_models.append(used)
        arr = _json_from_text(getattr(resp, "text", "") or "")
        mapping = {int(x.get("id", -1)): str(x.get("text", "")).strip() for x in arr if isinstance(x, dict)}
        for i, s in enumerate(part):
            txt = mapping.get(i, s.text) or s.text
            segs.append(Segment(s.start, s.end, txt, s.speaker, s.estimated))
    model_note = used_models[-1] if used_models else model
    return TextTrack(track.name + "｜Gemini順稿", segs, "", f"Gemini {model_note}", track.timed, track.estimated)


def transcribe_audio_for_review(
    audio_path: str,
    api_key: str,
    model: str = DEFAULT_TEXT_MODEL,
    progress: Callable[[str], None] | None = None,
) -> TextTrack:
    """上傳音訊做多人講者/核對軌；只有呼叫此函式時音訊才會上傳。"""
    client = _client(api_key)
    p = Path(audio_path)
    if not p.exists():
        raise RuntimeError("錄音檔不存在。")

    f = _upload_with_retry(client, p, progress)
    # Gemini File API 有時需要處理幾秒；輪詢不阻塞 UI，因為本函式在背景執行緒。
    for i in range(120):
        state = str(getattr(getattr(f, "state", None), "name", getattr(f, "state", ""))).upper()
        if "FAILED" in state:
            raise RuntimeError("Gemini 音檔處理失敗。")
        if not state or "ACTIVE" in state or "READY" in state:
            break
        if progress and i % 5 == 0:
            progress("Gemini 正在處理上傳音檔…")
        time.sleep(1)
        try:
            f = client.files.get(name=f.name)
        except Exception as exc:
            if not _retryable(exc):
                break

    prompt = """
請完整聽這段錄音，建立「核對用逐字稿」。
要求：
1. 請辨識不同說話者，以「講者1、講者2…」標示；若無法確定真實姓名，不要猜姓名。
2. 每段需有 start、end（秒數，可含小數）、speaker、text。
3. 保留中文、台語、英文、日文原意；中文輸出使用繁體中文（台灣用語）。
4. 只輸出 JSON 陣列，不要 markdown，不要解釋。
格式：[{"start":0.0,"end":3.2,"speaker":"講者1","text":"..."}]
"""

    resp, used = _generate_with_fallback(client, [f, prompt], model, progress=progress, label="Gemini 聽錄音核對")
    arr = _json_from_text(getattr(resp, "text", "") or "")
    segs: list[Segment] = []
    for x in arr:
        if not isinstance(x, dict):
            continue
        txt = str(x.get("text", "")).strip()
        if not txt:
            continue
        start = float(x.get("start", 0) or 0)
        end = float(x.get("end", start) or start)
        segs.append(Segment(start, max(start, end), txt, str(x.get("speaker", "")).strip(), False))
    if not segs:
        raise RuntimeError("Gemini 沒有回傳可用的時間軸逐字稿。")
    return TextTrack(p.stem + "｜Gemini智慧核對", segs, str(p), f"Gemini Audio {used}", True, False)
