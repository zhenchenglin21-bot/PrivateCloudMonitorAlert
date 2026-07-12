from __future__ import annotations

import json
from typing import Any, Dict, Optional

import requests


def _build_prompt(context: Dict[str, Any]) -> str:
    summary = {
        "host": context.get("host"),
        "current_state": context.get("current_state"),
        "previous_state": context.get("previous_state"),
        "top_feature": context.get("top_feature"),
        "confidence": context.get("confidence"),
        "duration_seconds": context.get("duration_seconds"),
        "triggered_features": context.get("triggered_features"),
        "feature_signals": context.get("feature_signals"),
        "feedback_profiles": context.get("feedback_profiles"),
    }
    return (
        "你是智能告警决策助手。请根据监控上下文判断是否需要告警，并只返回 JSON。"
        "JSON 必须包含 needs_alert(boolean)、level(string)、reason(string)、recommendation(string) 字段。\n"
        f"{json.dumps(summary, ensure_ascii=False)}"
    )


def _extract_json(content: str) -> Optional[Dict[str, Any]]:
    text = (content or "").strip()
    if not text:
        return None
    if text.startswith("```"):
        lines = [line for line in text.splitlines() if not line.strip().startswith("```")]
        text = "\n".join(lines).strip()
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else None
    except json.JSONDecodeError:
        return None


def _heuristic_decision(context: Dict[str, Any], cfg: Dict[str, Any]) -> Dict[str, Any]:
    confidence = float(context.get("confidence") or 0.0)
    top_level = str(context.get("top_level") or "warning").lower()
    min_confidence = float(cfg.get("min_confidence", 0.35))
    fixed_triggered = bool(context.get("anomaly_by_fixed_threshold"))
    dynamic_triggered = bool(context.get("anomaly_by_dynamic_threshold"))
    state_changed = bool(context.get("state_changed"))
    current_state = str(context.get("current_state") or "NORMAL").upper()
    previous_state = str(context.get("previous_state") or "NORMAL").upper()
    confidence_triggered = confidence >= min_confidence or top_level in {"critical", "alert"}
    state_transition_triggered = state_changed and current_state != "NORMAL" and previous_state != current_state
    needs_alert = (
        state_transition_triggered
        and (bool(context.get("anomaly")) or previous_state in {"WARNING", "ALERT", "CRITICAL"})
        and (fixed_triggered or dynamic_triggered or confidence_triggered or previous_state in {"WARNING", "ALERT", "CRITICAL"})
    )
    reason = str(context.get("reason") or "未发现明显异常。")
    recommendation = str(context.get("recommendation") or "建议结合业务日志与系统资源变化进一步排查。")
    return {
        "needs_alert": needs_alert,
        "level": top_level,
        "reason": reason,
        "recommendation": recommendation,
        "decision_source": "heuristic",
    }


def _llm_decision(context: Dict[str, Any], cfg: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not cfg.get("enabled"):
        return None
    api_key = cfg.get("api_key")
    base_url = cfg.get("base_url")
    model = cfg.get("model")
    if not api_key or not base_url or not model:
        return None

    endpoint = base_url.rstrip("/") + "/chat/completions"
    payload = {
        "model": model,
        "temperature": float(cfg.get("temperature", 0.2)),
        "messages": [
            {
                "role": "system",
                "content": "你是私有云监控平台的智能告警决策助手，请只输出 JSON。",
            },
            {
                "role": "user",
                "content": _build_prompt(context),
            },
        ],
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    try:
        response = requests.post(endpoint, json=payload, headers=headers, timeout=float(cfg.get("timeout_seconds", 10)))
        response.raise_for_status()
        body = response.json()
        content = body.get("choices", [{}])[0].get("message", {}).get("content", "")
        parsed = _extract_json(content)
        if not parsed:
            return None
        return {
            "needs_alert": bool(parsed.get("needs_alert")),
            "level": str(parsed.get("level") or context.get("top_level") or "warning").lower(),
            "reason": str(parsed.get("reason") or context.get("reason") or ""),
            "recommendation": str(parsed.get("recommendation") or context.get("recommendation") or ""),
            "decision_source": str(cfg.get("provider") or "llm").lower(),
        }
    except Exception:
        return None


def decide_alert(context: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
    cfg = config.get("decision_engine", {})
    llm_result = _llm_decision(context, cfg)
    if llm_result is not None:
        return llm_result
    return _heuristic_decision(context, cfg)
