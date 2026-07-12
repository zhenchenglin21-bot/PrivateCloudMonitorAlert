from __future__ import annotations

from typing import Any, Dict, List

import requests


DEFAULT_SEVERITY = {
    "cpu": "critical",
    "mem": "alert",
    "disk": "alert",
    "net_in": "warning",
    "net_out": "warning",
    "abnormal_process_cpu": "alert",
    "abnormal_process_mem": "alert",
}


def _normalize_severity_label(value: Any, fallback: str = "warning") -> str:
    text = str(value or "").strip().lower()
    if text in {"critical", "severe", "严重"}:
        return "critical"
    if text in {"alert", "high", "警报"}:
        return "alert"
    if text in {"warning", "warn", "medium", "low", "警告"}:
        return "warning"
    return fallback


def _normalize_runtime_metric_key(metric_key: str, metric_name: str) -> str:
    key = str(metric_key or "").strip().lower()
    name = str(metric_name or "").strip()
    lower_name = name.lower()

    if key in {"abnormal_process_cpu", "abnormal_process_mem"}:
        return key

    is_process = (
        "process" in lower_name
        or "proc" in lower_name
        or "pid" in lower_name
        or "进程" in name
        or "程序" in name
        or "杩涚▼" in name
    )
    is_cpu = "cpu" in lower_name
    is_mem = (
        "mem" in lower_name
        or "memory" in lower_name
        or "rss" in lower_name
        or "swap" in lower_name
        or "vms" in lower_name
        or "内存" in name
        or "鍐呭瓨" in name
    )

    if is_process and is_cpu:
        return "abnormal_process_cpu"
    if is_process and is_mem:
        return "abnormal_process_mem"
    return key


def fetch_runtime_static_rules(host: str, config: Dict[str, Any]) -> Dict[str, List[Dict[str, Any]]]:
    backend_cfg = config.get("backend", {})
    base_url = backend_cfg.get("url")
    api_key = backend_cfg.get("api_key")
    path = backend_cfg.get("rules_path")
    if not base_url or not api_key or not path:
        return {}

    try:
        response = requests.get(
            base_url.rstrip("/") + path,
            params={"host": host},
            headers={"X-API-Key": api_key},
            timeout=5,
        )
        response.raise_for_status()
        payload = response.json().get("data") or []
    except Exception as exc:
        print(f"[rules] fetch runtime rules failed host={host}: {exc}")
        return {}

    fixed_rules: Dict[str, List[Dict[str, Any]]] = {}
    for item in payload:
        if not isinstance(item, dict):
            continue
        metric_key = _normalize_runtime_metric_key(item.get("metricKey"), item.get("metric"))
        threshold_value = item.get("thresholdValue")
        if not metric_key or threshold_value is None:
            continue
        rule = {
            "name": item.get("name"),
            "metric": item.get("metric"),
            "threshold": item.get("threshold"),
            "upper": float(threshold_value),
            "severity": _normalize_severity_label(
                item.get("severity"),
                DEFAULT_SEVERITY.get(metric_key, "warning"),
            ),
        }
        fixed_rules.setdefault(metric_key, []).append(rule)
    return fixed_rules
