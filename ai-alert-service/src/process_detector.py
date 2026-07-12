from __future__ import annotations

from typing import Any, Dict, List, Optional

import numpy as np
from influxdb_client import InfluxDBClient

from .anomaly_detect import _evaluate_dynamic_threshold
from .data_loader import query_process_candidates, query_process_history
from .state_machine import state_rank


PROCESS_FIELD_LABELS = {
    "cpu_usage_percent": "CPU占用",
    "cpu_usage": "CPU占用",
    "cpu_percent": "CPU占用",
    "memory_usage": "内存占用",
    "memory_rss": "内存RSS",
    "memory_swap": "内存SWAP",
    "memory_vms": "内存VMS",
    "working_set": "内存工作集",
    "resident": "常驻内存",
}

CPU_FIELD_ALIASES = ["cpu_usage_percent", "cpu_usage", "cpu_percent"]
MEM_FIELD_ALIASES = ["memory_usage", "memory_rss", "memory_swap", "memory_vms", "working_set", "resident"]


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _normalize_process_value(field: str, value: float) -> float:
    lower = str(field or "").lower()
    if "memory" in lower or "rss" in lower or "swap" in lower or "vms" in lower or "resident" in lower:
        return value / 1024.0 / 1024.0
    return value


def _field_label(field: str) -> str:
    return PROCESS_FIELD_LABELS.get(str(field or "").lower(), field)


def _process_metric_key_by_field(field: str) -> str:
    lower = str(field or "").lower()
    if (
        "mem" in lower
        or "memory" in lower
        or "rss" in lower
        or "swap" in lower
        or "vms" in lower
        or "working_set" in lower
        or "resident" in lower
    ):
        return "abnormal_process_mem"
    return "abnormal_process_cpu"


def _metric_title(metric_key: str) -> str:
    if metric_key == "abnormal_process_mem":
        return "异常进程内存"
    return "异常进程CPU"


def _metric_unit(metric_key: str) -> str:
    if metric_key == "abnormal_process_mem":
        return "MB"
    return "%"


def _expand_process_fields(fields: List[str]) -> List[str]:
    raw = [str(item or "").strip() for item in fields if str(item or "").strip()]
    if not raw:
        return [*CPU_FIELD_ALIASES, *MEM_FIELD_ALIASES]

    expanded: List[str] = []
    has_cpu = False
    has_mem = False
    for item in raw:
        lower = item.lower()
        expanded.append(item)
        if "cpu" in lower:
            has_cpu = True
        if "mem" in lower or "memory" in lower or "rss" in lower or "swap" in lower or "vms" in lower:
            has_mem = True

    if has_cpu:
        expanded.extend(CPU_FIELD_ALIASES)
    if has_mem:
        expanded.extend(MEM_FIELD_ALIASES)

    ordered = []
    seen = set()
    for item in expanded:
        key = item.lower()
        if key in seen:
            continue
        seen.add(key)
        ordered.append(item)
    return ordered


def _state_to_level(state: str) -> str:
    if state == "CRITICAL":
        return "critical"
    if state == "ALERT":
        return "alert"
    return "warning"


def _severity_to_state(severity: str) -> str:
    mapping = {
        "warning": "WARNING",
        "warn": "WARNING",
        "low": "WARNING",
        "medium": "WARNING",
        "alert": "ALERT",
        "high": "ALERT",
        "critical": "CRITICAL",
        "severe": "CRITICAL",
    }
    return mapping.get(str(severity or "warning").lower(), "WARNING")


def _normalize_severity_label(severity: str) -> str:
    state = _severity_to_state(severity)
    if state == "CRITICAL":
        return "critical"
    if state == "ALERT":
        return "alert"
    return "warning"


def _resolve_static_severity(metric_key: str, fallback_severity: str, breach_ratio: float, static_policy_cfg: Dict[str, Any]) -> str:
    feature_policy = static_policy_cfg.get(metric_key) or {}
    fallback = _normalize_severity_label(fallback_severity)
    base = _normalize_severity_label(str(feature_policy.get("base", fallback)))
    immediate_critical = bool(feature_policy.get("immediate_critical", False))
    alert_ratio = max(0.0, _safe_float(feature_policy.get("alert_ratio", 0.0), 0.0))
    critical_ratio = max(alert_ratio, _safe_float(feature_policy.get("critical_ratio", 0.2), 0.2))

    if immediate_critical and breach_ratio > 0:
        return "critical"
    if breach_ratio >= critical_ratio:
        return "critical"
    if breach_ratio >= alert_ratio:
        return "alert"
    return base


def _build_process_dynamic_cfg(config: Dict[str, Any], process_cfg: Dict[str, Any]) -> Dict[str, Any]:
    dynamic_cfg = dict(config.get("dynamic_threshold") or {})
    process_dynamic_raw = process_cfg.get("dynamic_fusion") or {}
    process_dynamic_cfg = process_dynamic_raw if isinstance(process_dynamic_raw, dict) else {}
    dynamic_cfg.update(process_dynamic_cfg)
    dynamic_cfg["enabled"] = bool(dynamic_cfg.get("enabled", True))
    return dynamic_cfg


def _summarize_group_signal(metric_key: str, entries: List[Dict[str, Any]]) -> Dict[str, Any]:
    title = _metric_title(metric_key)
    unit = _metric_unit(metric_key)
    static_entries = [entry for entry in entries if str(entry.get("threshold_type")) == "static"]
    dynamic_entries = [entry for entry in entries if str(entry.get("threshold_type")) == "dynamic"]
    worst = max(entries, key=lambda item: (state_rank(str(item.get("state") or "NORMAL")), float(item.get("score") or 0.0)))

    if static_entries:
        static_entries = sorted(static_entries, key=lambda item: float(item.get("current") or 0.0), reverse=True)
        detail_parts = []
        for entry in static_entries[:6]:
            process_name = str(entry.get("process_name") or "-")
            current_value = _safe_float(entry.get("current"))
            threshold_value = _safe_float(entry.get("threshold_value"))
            detail_parts.append(f"{process_name}({current_value:.2f}{unit} > {threshold_value:.2f}{unit})")
        reason = (
            f"{title}触发静态阈值告警，以下进程超出阈值："
            + "，".join(detail_parts)
            + f"。共 {len(static_entries)} 个异常进程。"
        )
        recommendation = (
            f"优先按该静态阈值规则排查 {title}：先定位上述进程是否业务突增或异常循环，"
            "再执行限流、降并发、重启或扩容等处置。"
        )
        threshold_type = "static"
        threshold_value = min(_safe_float(item.get("threshold_value")) for item in static_entries)
        static_threshold = {"breached": True, "threshold": threshold_value}
    else:
        dynamic_entries = sorted(dynamic_entries, key=lambda item: float(item.get("current") or 0.0), reverse=True)
        detail_parts = []
        for entry in dynamic_entries[:6]:
            process_name = str(entry.get("process_name") or "-")
            current_value = _safe_float(entry.get("current"))
            dynamic_threshold = _safe_float(entry.get("threshold_value"))
            detail_parts.append(f"{process_name}({current_value:.2f}{unit} > 动态阈值{dynamic_threshold:.2f}{unit})")
        reason = (
            f"{title}触发动态阈值告警，以下进程持续异常："
            + "，".join(detail_parts)
            + f"。共 {len(dynamic_entries)} 个异常进程。"
        )
        recommendation = (
            f"先聚焦 {title}异常进程的共同特征（同应用、同时间窗、同主机资源争抢），"
            "再结合日志与调用链定位根因，必要时调整动态阈值参数。"
        )
        threshold_type = "dynamic"
        threshold_value = max(_safe_float(item.get("threshold_value")) for item in dynamic_entries) if dynamic_entries else None
        static_threshold = {"breached": False, "threshold": None}

    dynamic_threshold_values = [
        _safe_float(entry.get("dynamic_threshold", {}).get("threshold"))
        for entry in entries
        if (entry.get("dynamic_threshold") or {}).get("threshold") is not None
    ]
    dynamic_threshold = {
        "breached": bool(dynamic_entries),
        "threshold": max(dynamic_threshold_values) if dynamic_threshold_values else None,
        "final_score": max(
            (
                _safe_float((entry.get("dynamic_threshold") or {}).get("final_score"), 0.0)
                for entry in entries
            ),
            default=0.0,
        ),
        "detector_scores": (worst.get("dynamic_threshold") or {}).get("detector_scores") or {},
        "detector_weights": (worst.get("dynamic_threshold") or {}).get("detector_weights") or {},
    }

    return {
        "feature": metric_key,
        "metric_key": metric_key,
        "state": str(worst.get("state") or "WARNING"),
        "level": _state_to_level(str(worst.get("state") or "WARNING")),
        "score": float(worst.get("score") or 1.0),
        "threshold_type": threshold_type,
        "threshold_value": threshold_value,
        "current": _safe_float(worst.get("current")),
        "mean": _safe_float(worst.get("mean")),
        "std": _safe_float(worst.get("std")),
        "trend": _safe_float(worst.get("trend")),
        "adjusted_value": _safe_float(worst.get("adjusted_value")),
        "breached": True,
        "reasons": [reason],
        "recommendation": recommendation,
        "processes": entries,
        "process_count": len(entries),
        "high_threshold": worst.get("high_threshold"),
        "static_threshold": static_threshold,
        "dynamic_threshold": dynamic_threshold,
        "prediction": {"breached": False},
    }


def detect_process_anomaly(
    client: InfluxDBClient,
    bucket: str,
    host: str,
    query_host: str,
    role: str,
    history_minutes: int,
    config: Dict[str, Any],
    feedback_profile: Optional[Dict[str, Any]] = None,
    fixed_rules: Optional[Dict[str, List[Dict[str, Any]]]] = None,
) -> Optional[Dict[str, Any]]:
    process_cfg = config.get("process_detection", {})
    if not process_cfg.get("enabled", True):
        return None

    feedback_profile = feedback_profile or {}
    fixed_rules = fixed_rules or {}
    static_policy_cfg = config.get("static_severity_policy", {})
    top_candidates = max(1, int(process_cfg.get("top_candidates", 5)))
    raw_fields: List[str] = list(process_cfg.get("fields") or ["cpu_usage_percent", "memory_usage"])
    fields = _expand_process_fields(raw_fields)
    candidates = query_process_candidates(client, bucket, query_host, role, host, history_minutes, fields, top_candidates)
    if not candidates:
        return None

    dynamic_cfg = _build_process_dynamic_cfg(config, process_cfg)
    z_cfg = dict(config.get("zscore") or {})
    min_points = max(5, int(process_cfg.get("min_points", 8)))
    groups: Dict[str, List[Dict[str, Any]]] = {"abnormal_process_cpu": [], "abnormal_process_mem": []}

    for candidate in candidates:
        process_name = str(candidate.get("process_name") or "").strip()
        field = str(candidate.get("field") or "").strip()
        current_value = _normalize_process_value(field, float(candidate.get("value") or 0.0))
        history = query_process_history(client, bucket, query_host, role, host, history_minutes, process_name, field)
        if history.empty or len(history) < min_points:
            continue

        values = history["_value"].to_numpy(dtype=np.float32)
        values = np.array([_normalize_process_value(field, float(item)) for item in values], dtype=np.float32)
        recent = values[-max(min_points, int(process_cfg.get("window_points", min_points))):]
        metric_key = _process_metric_key_by_field(field)
        dynamic_signal = _evaluate_dynamic_threshold(
            metric_key,
            recent,
            values,
            current_value,
            dynamic_cfg,
            z_cfg,
            feedback_profile,
        )
        mean_value = _safe_float(dynamic_signal.get("mean"))
        std_value = _safe_float(dynamic_signal.get("std"))
        trend_value = _safe_float(dynamic_signal.get("trend"))
        dynamic_breached = bool(dynamic_signal.get("breached"))
        dynamic_threshold_value = dynamic_signal.get("warning_threshold")
        state_hint = str(dynamic_signal.get("state") or "WARNING").upper()
        if state_hint in {"ALERT", "CRITICAL"} and dynamic_signal.get("alert_threshold") is not None:
            dynamic_threshold_value = dynamic_signal.get("alert_threshold")

        process_rules = fixed_rules.get(metric_key) or []
        static_threshold = None

        for rule in process_rules:
            upper = rule.get("upper")
            if upper is None:
                continue
            upper_value = _safe_float(upper)
            if current_value > upper_value and (static_threshold is None or upper_value < static_threshold):
                static_threshold = upper_value

        static_breached = static_threshold is not None and current_value > _safe_float(static_threshold)
        if not static_breached and not dynamic_breached:
            continue

        if static_breached:
            threshold_value = _safe_float(static_threshold)
            breach_base = max(abs(threshold_value), 1.0)
            breach_ratio = abs(current_value - threshold_value) / breach_base
            rule_severity = "alert"
            for rule in process_rules:
                upper = rule.get("upper")
                if upper is None:
                    continue
                if abs(_safe_float(upper) - threshold_value) < 1e-9:
                    rule_severity = str(rule.get("severity", "alert"))
                    break
            if str(rule_severity).strip():
                resolved_severity = _normalize_severity_label(rule_severity)
            else:
                resolved_severity = _resolve_static_severity(metric_key, rule_severity, breach_ratio, static_policy_cfg)
            state = _severity_to_state(resolved_severity)
            threshold_type = "static"
            score_value = 2.4 if state == "CRITICAL" else 1.8 if state == "ALERT" else 1.2
        else:
            state = str(dynamic_signal.get("state") or "WARNING").upper()
            if state_rank(state) < state_rank("WARNING"):
                state = "WARNING"
            threshold_type = "dynamic"
            threshold_value = _safe_float(dynamic_threshold_value) if dynamic_threshold_value is not None else None
            dynamic_final_score = max(0.0, min(1.0, _safe_float(dynamic_signal.get("final_score"), 0.0)))
            score_value = 1.0 + 2.0 * dynamic_final_score

        groups[metric_key].append(
            {
                "process_name": process_name,
                "field": field,
                "field_label": _field_label(field),
                "current": current_value,
                "mean": mean_value,
                "std": std_value,
                "trend": trend_value,
                "adjusted_value": _safe_float(dynamic_signal.get("adjusted_value"), current_value),
                "state": state,
                "score": score_value,
                "threshold_type": threshold_type,
                "threshold_value": threshold_value,
                "high_threshold": dynamic_signal.get("high_threshold"),
                "final_score": _safe_float(dynamic_signal.get("final_score"), 0.0),
                "static_threshold": {
                    "breached": static_breached,
                    "threshold": _safe_float(static_threshold) if static_threshold is not None else None,
                },
                "dynamic_threshold": {
                    "breached": dynamic_breached,
                    "threshold": _safe_float(dynamic_threshold_value) if dynamic_threshold_value is not None else None,
                    "z_score": _safe_float(dynamic_signal.get("z_score"), 0.0),
                    "final_score": _safe_float(dynamic_signal.get("final_score"), 0.0),
                    "detector_scores": dynamic_signal.get("detector_scores") or {},
                    "detector_weights": dynamic_signal.get("detector_weights") or {},
                    "warning_score_threshold": dynamic_signal.get("warning_score_threshold"),
                    "alert_score_threshold": dynamic_signal.get("alert_score_threshold"),
                    "critical_score_threshold": dynamic_signal.get("critical_score_threshold"),
                },
            }
        )

    grouped_signals: Dict[str, Dict[str, Any]] = {}
    all_reasons: List[str] = []
    for metric_key, entries in groups.items():
        if not entries:
            continue
        signal = _summarize_group_signal(metric_key, entries)
        grouped_signals[metric_key] = signal
        all_reasons.extend(signal.get("reasons") or [])

    if not grouped_signals:
        return None

    top_signal = max(
        grouped_signals.values(),
        key=lambda item: (state_rank(str(item.get("state") or "NORMAL")), float(item.get("score") or 0.0)),
    )

    return {
        **top_signal,
        "feature": "abnormal_process",
        "grouped_signals": grouped_signals,
        "reasons": all_reasons,
        "reason": "；".join(all_reasons),
    }
