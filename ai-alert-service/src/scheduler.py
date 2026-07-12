from __future__ import annotations

import os
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import tensorflow as tf
from influxdb_client import InfluxDBClient

from .alert_manager import build_alert, emit_alert
from .anomaly_detect import detect_anomaly
from .data_loader import query_metrics
from .decision_engine import decide_alert
from .feedback_loop import fetch_feedback_profiles
from .flux_target import build_role_filter, build_target_filter
from .model_train import train_model
from .process_detector import detect_process_anomaly
from .rule_loader import fetch_runtime_static_rules
from .state_machine import apply_state_machine, state_rank, state_to_level
from .target_loader import fetch_runtime_targets
from .utils import load_config


@dataclass(frozen=True)
class TargetDescriptor:
    host: str
    role: str = "host"
    query_host: str = ""
    parent_host: str = ""

    @property
    def key(self) -> str:
        return f"{self.role}:{self.host}"

    @property
    def display_name(self) -> str:
        return self.host

    def resolved_query_host(self) -> str:
        return self.query_host or self.host


@dataclass
class DetectionState:
    current_state: str = "NORMAL"
    previous_state: str = "NORMAL"
    active_alert: bool = False
    active_fingerprint: Optional[str] = None
    last_notified_fingerprint: Optional[str] = None
    active_since: Optional[datetime] = None
    last_emit_at: Optional[datetime] = None
    abnormal_streak: int = 0
    normal_streak: int = 0
    last_abnormal_source: Optional[str] = None
    last_context: Optional[Dict[str, object]] = None
    feature_last_emit_at: Dict[str, datetime] = field(default_factory=dict)
    feature_transition_last_emit_at: Dict[str, datetime] = field(default_factory=dict)
    process_last_emit_at: Dict[str, datetime] = field(default_factory=dict)
    static_feature_states: Dict[str, str] = field(default_factory=dict)
    dynamic_target_state: str = "NORMAL"
    dynamic_target_streak: int = 0
    dynamic_feature_machines: Dict[str, "FeatureMachineState"] = field(default_factory=dict)
    dynamic_feature_last_context: Dict[str, Dict[str, object]] = field(default_factory=dict)
    dynamic_feature_active_fingerprint: Dict[str, str] = field(default_factory=dict)
    dynamic_feature_transition_last_emit_at: Dict[str, datetime] = field(default_factory=dict)


@dataclass
class FeatureMachineState:
    current_state: str = "NORMAL"
    previous_state: str = "NORMAL"
    active_since: Optional[datetime] = None
    abnormal_streak: int = 0
    normal_streak: int = 0
    last_abnormal_source: Optional[str] = None
    dynamic_target_state: str = "NORMAL"
    dynamic_target_streak: int = 0


def _sanitize_filename(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def _target_model_path(config: dict, target: TargetDescriptor) -> str:
    base_path = str(config["model"]["model_path"])
    root, ext = os.path.splitext(base_path)
    suffix = _sanitize_filename(target.key)
    return f"{root}_{suffix}{ext or '.h5'}"


def _format_float(value: object) -> str:
    if isinstance(value, (int, float, np.floating)):
        return f"{float(value):.4f}"
    return "--"


def _print_detection_log(
    target: TargetDescriptor,
    detection: Dict[str, object],
    state: DetectionState,
    row_count: int,
    min_points: int,
) -> None:
    feature_signals = detection.get("feature_signals") or {}
    latest_parts = []
    for feature, signal in feature_signals.items():
        latest_parts.append(
            f"{feature}={_format_float(signal.get('current'))}/mean={_format_float(signal.get('mean'))}"
        )

    print(
        f"[detect] target={target.key} query_host={target.resolved_query_host()} rows={row_count}/{min_points} "
        f"candidate={detection.get('candidate_state') or 'NORMAL'} "
        f"state={state.current_state} abnormal_streak={state.abnormal_streak} "
        f"normal_streak={state.normal_streak} "
        f"fixed={detection.get('fixed_triggered_features') or []} "
        f"triggered={detection.get('triggered_features') or []} "
        f"latest[{', '.join(latest_parts)}]"
    )
    print(
        f"[reason] target={target.key} top_feature={detection.get('top_feature') or '--'} "
        f"value={_format_float(detection.get('top_value'))} "
        f"threshold={_format_float(detection.get('top_threshold_value'))} "
        f"trend={_format_float(detection.get('top_trend'))} "
        f"reason={detection.get('reason') or '当前未检测到异常'}"
    )


def _merge_process_signal(detection: Dict[str, object], process_signal: Optional[Dict[str, object]]) -> Dict[str, object]:
    if not process_signal:
        return detection

    feature_signals = dict(detection.get("feature_signals") or {})
    grouped_signals = process_signal.get("grouped_signals") or {}
    if grouped_signals:
        for metric_key, signal in grouped_signals.items():
            feature_signals[str(metric_key)] = signal
    else:
        feature_signals["abnormal_process"] = process_signal

    triggered_features = list(detection.get("triggered_features") or [])
    fixed_triggered_features = list(detection.get("fixed_triggered_features") or [])

    if grouped_signals:
        for metric_key, signal in grouped_signals.items():
            feature_name = str(metric_key)
            if feature_name not in triggered_features:
                triggered_features.append(feature_name)
            if bool((signal.get("static_threshold") or {}).get("breached")) and feature_name not in fixed_triggered_features:
                fixed_triggered_features.append(feature_name)
    else:
        if "abnormal_process" not in triggered_features:
            triggered_features.append("abnormal_process")
        if bool((process_signal.get("static_threshold") or {}).get("breached")) and "abnormal_process" not in fixed_triggered_features:
            fixed_triggered_features.append("abnormal_process")

    process_dynamic_triggered = False
    if grouped_signals:
        process_dynamic_triggered = any(
            bool((signal.get("dynamic_threshold") or {}).get("breached")) for signal in grouped_signals.values()
        )
    else:
        process_dynamic_triggered = bool((process_signal.get("dynamic_threshold") or {}).get("breached"))

    reasons = []
    if grouped_signals:
        for signal in grouped_signals.values():
            reasons.extend(signal.get("reasons") or [])
    else:
        reasons = list(process_signal.get("reasons") or [])

    combined_reason = str(detection.get("reason") or "")
    if reasons:
        process_reason = "; ".join(str(item) for item in reasons)
        combined_reason = (
            f"{combined_reason}; {process_reason}"
            if combined_reason and combined_reason != "当前未检测到异常"
            else process_reason
        )

    top_feature = detection.get("top_feature")
    top_signal = feature_signals.get(str(top_feature or ""), {})
    candidate_process_signal = (
        max(
            grouped_signals.values(),
            key=lambda item: (
                state_rank(str(item.get("state") or "NORMAL")),
                float(item.get("score") or 0.0),
            ),
        )
        if grouped_signals
        else process_signal
    )
    should_promote = (
        not top_signal
        or candidate_process_signal.get("state") == "CRITICAL"
        or float(candidate_process_signal.get("score") or 0.0) > float(top_signal.get("score") or 0.0)
    )

    candidate_state = str(detection.get("candidate_state") or "NORMAL")
    if candidate_process_signal.get("state") == "CRITICAL":
        candidate_state = "CRITICAL"
    elif should_promote and str(candidate_process_signal.get("state") or "NORMAL") != "NORMAL":
        candidate_state = str(candidate_process_signal.get("state"))

    merged = {
        **detection,
        "anomaly": True,
        "anomaly_by_confidence": True,
        "anomaly_level": candidate_state,
        "anomaly_by_dynamic_threshold": bool(detection.get("anomaly_by_dynamic_threshold")) or process_dynamic_triggered,
        "feature_signals": feature_signals,
        "triggered_features": triggered_features,
        "fixed_triggered_features": fixed_triggered_features,
        "candidate_state": candidate_state,
        "reason": combined_reason,
    }
    if should_promote:
        merged.update(
            {
                "top_feature": str(candidate_process_signal.get("feature") or "abnormal_process"),
                "top_level": candidate_process_signal.get("level"),
                "top_value": candidate_process_signal.get("current"),
                "top_mean": candidate_process_signal.get("mean"),
                "top_std": candidate_process_signal.get("std"),
                "top_trend": candidate_process_signal.get("trend"),
                "top_threshold_type": candidate_process_signal.get("threshold_type"),
                "top_threshold_value": candidate_process_signal.get("threshold_value"),
                "top_high_threshold": candidate_process_signal.get("high_threshold"),
                "top_dynamic_score": (candidate_process_signal.get("dynamic_threshold") or {}).get("final_score"),
                "top_detector_scores": (candidate_process_signal.get("dynamic_threshold") or {}).get("detector_scores"),
                "top_detector_weights": (candidate_process_signal.get("dynamic_threshold") or {}).get("detector_weights"),
                "recommendation": candidate_process_signal.get("recommendation"),
            }
        )
    return merged


def build_queries(target: TargetDescriptor, history_minutes: int):
    start = f"-{history_minutes}m"
    role_filter = build_role_filter(target.role)
    target_filter = build_target_filter(target.resolved_query_host(), target.role, target.host)
    cpu_fields = ["usage_user", "usage_active", "usage_total", "usage_percent", "cpu_usage"]
    cpu_queries = [
        f"""
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "cpu")
  |> filter(fn: (r) => r._field == "{field}")
{role_filter}{target_filter}  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
"""
        for field in cpu_fields
    ]
    root_path_values = ['"/"', '"/rootfs"', '"C:\\\\"', '"C:/"', '"C:"']
    root_path_filter = " or ".join(
        [
            f'(exists r.path and ({ " or ".join([f"r.path == {value}" for value in root_path_values]) }))',
            f'(exists r.mount and ({ " or ".join([f"r.mount == {value}" for value in root_path_values]) }))',
            f'(exists r.mountpoint and ({ " or ".join([f"r.mountpoint == {value}" for value in root_path_values]) }))',
        ]
    )
    ignored_fs_filter = (
        '(not exists r.fstype) or (r.fstype != "tmpfs" and r.fstype != "devtmpfs" and '
        'r.fstype != "squashfs" and r.fstype != "overlay" and r.fstype != "aufs" and r.fstype != "ramfs")'
    )
    disk_root_queries = [
        f"""
import "types"
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "disk")
  |> filter(fn: (r) => r._field == "{field}")
{role_filter}{target_filter}  |> filter(fn: (r) => types.isType(v: r._value, type: "float") or types.isType(v: r._value, type: "int") or types.isType(v: r._value, type: "uint"))
  |> filter(fn: (r) => {ignored_fs_filter})
  |> filter(fn: (r) => {root_path_filter})
  |> aggregateWindow(every: 10s, fn: last, createEmpty: false)
  |> group(columns: ["_time"])
  |> mean(column: "_value")
"""
        for field in ["used_percent", "usage_percent", "filesystem_usage"]
    ]
    disk_total_query = f"""
import "types"
raw = from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "disk")
  |> filter(fn: (r) => r._field == "used" or r._field == "total")
{role_filter}{target_filter}  |> filter(fn: (r) => types.isType(v: r._value, type: "float") or types.isType(v: r._value, type: "int") or types.isType(v: r._value, type: "uint"))
  |> filter(fn: (r) => {ignored_fs_filter})
  |> aggregateWindow(every: 10s, fn: last, createEmpty: false)

raw
  |> group(columns: ["_time", "_field"])
  |> sum(column: "_value")
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> filter(fn: (r) => exists r.total and r.total > 0.0 and exists r.used)
  |> map(fn: (r) => ({{ r with _value: float(v: r.used) / float(v: r.total) * 100.0 }}))
"""
    disk_legacy_query = f"""
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "disk")
  |> filter(fn: (r) => r._field == "used_percent")
{role_filter}{target_filter}  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
"""

    if str(target.role or "").strip().lower() == "container":
        disk_queries = [
            f"""
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "disk")
  |> filter(fn: (r) => r._field == "used_percent")
{role_filter}{target_filter}  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
""",
            f"""
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "disk")
  |> filter(fn: (r) => r._field == "usage_percent" or r._field == "filesystem_usage")
{role_filter}{target_filter}  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
""",
        ]
    else:
        disk_queries = [*disk_root_queries, disk_total_query, disk_legacy_query]

    return {
        "cpu": cpu_queries,
        "mem": f"""
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "mem")
  |> filter(fn: (r) => r._field == "used_percent")
{role_filter}{target_filter}  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
""",
        "disk": disk_queries,
        "net_in": f"""
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "net" or r._measurement == "network" or r._measurement == "network_interface")
  |> filter(fn: (r) => r._field == "bytes_recv" or r._field == "bytes_received" or r._field == "rx_bytes")
{role_filter}{target_filter}  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
  |> derivative(unit: 1s, nonNegative: true)
  |> map(fn: (r) => ({{ r with _value: float(v: r._value) * 8.0 / 1000000.0 }}))
""",
        "net_out": f"""
from(bucket: "$bucket")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "net" or r._measurement == "network" or r._measurement == "network_interface")
  |> filter(fn: (r) => r._field == "bytes_sent" or r._field == "bytes_send" or r._field == "tx_bytes")
{role_filter}{target_filter}  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
  |> derivative(unit: 1s, nonNegative: true)
  |> map(fn: (r) => ({{ r with _value: float(v: r._value) * 8.0 / 1000000.0 }}))
""",
    }


def load_or_train_model(series: np.ndarray, config: dict, target: TargetDescriptor):
    prediction_cfg = config.get("prediction", {})
    if not prediction_cfg.get("enabled", True):
        return None

    model_cfg = config["model"]
    path = _target_model_path(config, target)
    try:
        return tf.keras.models.load_model(path)
    except Exception:
        model = train_model(
            series,
            model_cfg["window_size"],
            model_cfg["lstm_units"],
            1,
            model_cfg["batch_size"],
        )
        if model:
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            model.save(path)
        return model


def _build_context(
    target: TargetDescriptor,
    config: dict,
    detection: Dict[str, object],
    feedback_profiles: Dict[str, object],
    state: DetectionState,
) -> Dict[str, object]:
    now = datetime.now(timezone.utc)
    active_since = state.active_since or now
    duration_seconds = max(0, int((now - active_since).total_seconds()))
    top_feature = detection.get("top_feature")
    top_signal = (detection.get("feature_signals") or {}).get(top_feature or "", {})
    top_threshold_type = top_signal.get("threshold_type") or detection.get("top_threshold_type")
    top_reasons = [str(item) for item in (top_signal.get("reasons") or []) if str(item).strip()]
    context_reason = detection.get("reason")
    context_recommendation = detection.get("recommendation")

    if str(top_threshold_type or "").lower() == "static":
        if top_reasons:
            context_reason = "；".join(top_reasons)
        if top_signal.get("recommendation"):
            context_recommendation = top_signal.get("recommendation")

    return {
        "host": target.display_name,
        "target_role": target.role,
        "query_host": target.resolved_query_host(),
        "rule_name": str(config.get("rule_name", "AI intelligent alert")),
        "anomaly": bool(detection.get("anomaly")),
        "current_state": state.current_state,
        "previous_state": state.previous_state,
        "state_changed": state.current_state != state.previous_state,
        "anomaly_by_fixed_threshold": bool(detection.get("anomaly_by_fixed_threshold")),
        "anomaly_by_dynamic_threshold": bool(detection.get("anomaly_by_dynamic_threshold")),
        "confidence": float(detection.get("confidence") or 0.0),
        "top_feature": top_feature,
        "top_level": state_to_level(state.current_state),
        "top_value": detection.get("top_value"),
        "top_mean": detection.get("top_mean"),
        "top_std": detection.get("top_std"),
        "top_trend": detection.get("top_trend"),
        "top_threshold_type": top_threshold_type,
        "top_threshold_value": detection.get("top_threshold_value"),
        "top_signal": top_signal,
        "reason": context_reason,
        "recommendation": context_recommendation,
        "triggered_features": detection.get("triggered_features") or [],
        "feature_signals": detection.get("feature_signals") or {},
        "feedback_profiles": feedback_profiles,
        "duration_seconds": duration_seconds,
    }


def _build_feature_context(
    target: TargetDescriptor,
    config: dict,
    detection: Dict[str, object],
    feedback_profiles: Dict[str, object],
    state: DetectionState,
    feature_name: str,
    signal_override: Optional[Dict[str, object]] = None,
) -> Dict[str, object]:
    feature_signals = detection.get("feature_signals") or {}
    signal = signal_override or feature_signals.get(feature_name) or {}
    now = datetime.now(timezone.utc)
    active_since = state.active_since or now
    duration_seconds = max(0, int((now - active_since).total_seconds()))

    return {
        "host": target.display_name,
        "target_role": target.role,
        "query_host": target.resolved_query_host(),
        "rule_name": str(config.get("rule_name", "AI intelligent alert")),
        "anomaly": bool(signal.get("breached")),
        "current_state": state.current_state,
        "previous_state": state.previous_state,
        "state_changed": state.current_state != state.previous_state,
        "anomaly_by_fixed_threshold": bool((signal.get("static_threshold") or {}).get("breached")),
        "anomaly_by_dynamic_threshold": bool((signal.get("dynamic_threshold") or {}).get("breached")),
        "confidence": float(detection.get("confidence") or 0.0),
        "top_feature": feature_name,
        "top_level": str(signal.get("level") or state_to_level(state.current_state)),
        "top_value": signal.get("current"),
        "top_mean": signal.get("mean"),
        "top_std": signal.get("std"),
        "top_trend": signal.get("trend"),
        "top_threshold_type": signal.get("threshold_type"),
        "top_threshold_value": signal.get("threshold_value"),
        "top_signal": signal,
        "reason": "；".join(str(item) for item in (signal.get("reasons") or []) if str(item).strip()),
        "recommendation": signal.get("recommendation"),
        "triggered_features": [feature_name],
        "feature_signals": {feature_name: signal},
        "feedback_profiles": feedback_profiles,
        "duration_seconds": duration_seconds,
    }


def _build_dynamic_context(
    target: TargetDescriptor,
    config: dict,
    detection: Dict[str, object],
    feedback_profiles: Dict[str, object],
    state: DetectionState,
) -> Optional[Dict[str, object]]:
    feature_signals = detection.get("feature_signals") or {}
    dynamic_features = []
    for feature_name, signal in feature_signals.items():
        dynamic_signal = signal.get("dynamic_threshold") or {}
        if bool(dynamic_signal.get("breached")) or bool(dynamic_signal.get("trend_breached")):
            dynamic_features.append((feature_name, signal))

    if not dynamic_features:
        return None

    top_feature, top_signal = max(
        dynamic_features,
        key=lambda item: (
            float(item[1].get("score") or 0.0),
            abs(float(item[1].get("trend") or 0.0)),
        ),
    )
    now = datetime.now(timezone.utc)
    active_since = state.active_since or now
    duration_seconds = max(0, int((now - active_since).total_seconds()))
    reasons = []
    recommendations = []
    triggered_features = []

    for feature_name, signal in dynamic_features:
        triggered_features.append(feature_name)
        reasons.extend(str(item) for item in (signal.get("reasons") or []) if str(item).strip())
        recommendation = str(signal.get("recommendation") or "").strip()
        if recommendation and recommendation not in recommendations:
            recommendations.append(recommendation)

    return {
        "host": target.display_name,
        "target_role": target.role,
        "query_host": target.resolved_query_host(),
        "rule_name": str(config.get("rule_name", "AI intelligent alert")),
        "anomaly": True,
        "current_state": state.current_state,
        "previous_state": state.previous_state,
        "state_changed": state.current_state != state.previous_state,
        "anomaly_by_fixed_threshold": False,
        "anomaly_by_dynamic_threshold": True,
        "confidence": float(detection.get("confidence") or 0.0),
        "top_feature": top_feature,
        "top_level": state_to_level(state.current_state),
        "top_value": top_signal.get("current"),
        "top_mean": top_signal.get("mean"),
        "top_std": top_signal.get("std"),
        "top_trend": top_signal.get("trend"),
        "top_threshold_type": "dynamic",
        "top_threshold_value": top_signal.get("threshold_value"),
        "top_signal": top_signal,
        "reason": "；".join(reasons) if reasons else "检测到动态阈值异常。",
        "recommendation": "；".join(recommendations) if recommendations else "建议结合日志与业务负载进一步排查。",
        "triggered_features": triggered_features,
        "feature_signals": {feature: signal for feature, signal in dynamic_features},
        "feedback_profiles": feedback_profiles,
        "duration_seconds": duration_seconds,
    }


def _build_fingerprint(target: TargetDescriptor, context: Dict[str, object]) -> str:
    return f"{target.key}:{context.get('top_feature') or 'unknown'}:{context.get('current_state') or 'NORMAL'}"


def _is_grouped_process_feature(feature_name: str) -> bool:
    return feature_name in {"abnormal_process_cpu", "abnormal_process_mem"}


def _process_metric_label(feature_name: str) -> str:
    if feature_name == "abnormal_process_mem":
        return "异常进程内存"
    return "异常进程CPU"


def _process_metric_unit(feature_name: str) -> str:
    if feature_name == "abnormal_process_mem":
        return "MB"
    return "%"


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _normalize_state(value: Any, default: str = "NORMAL") -> str:
    candidate = str(value or default).upper()
    if candidate in {"NORMAL", "WARNING", "ALERT", "CRITICAL"}:
        return candidate
    return default


def _get_dynamic_feature_machine(state: DetectionState, feature_name: str) -> FeatureMachineState:
    machine = state.dynamic_feature_machines.get(feature_name)
    if machine is None:
        machine = FeatureMachineState()
        state.dynamic_feature_machines[feature_name] = machine
    return machine


def _build_dynamic_feature_detection(feature_name: str, signal: Dict[str, object]) -> Dict[str, object]:
    dynamic_signal = signal.get("dynamic_threshold") or {}
    dynamic_active = bool(dynamic_signal.get("breached")) or bool(dynamic_signal.get("trend_breached"))
    candidate_state = _normalize_state(signal.get("state"), "NORMAL") if dynamic_active else "NORMAL"
    return {
        "feature_signals": {feature_name: signal} if signal else {},
        "fixed_triggered_features": [],
        "candidate_state": candidate_state,
        "anomaly_level": candidate_state,
        "anomaly_by_dynamic_threshold": dynamic_active,
    }


def _build_grouped_process_reason(
    feature_name: str,
    threshold_type: str,
    processes: List[Dict[str, object]],
    suppressed_count: int = 0,
) -> str:
    label = _process_metric_label(feature_name)
    unit = _process_metric_unit(feature_name)
    sorted_processes = sorted(processes, key=lambda item: _safe_float(item.get("current")), reverse=True)
    detail = []
    for item in sorted_processes[:6]:
        process_name = str(item.get("process_name") or "-")
        current = _safe_float(item.get("current"))
        threshold = _safe_float(item.get("threshold_value"))
        if threshold > 0:
            detail.append(f"{process_name}({current:.2f}{unit}>{threshold:.2f}{unit})")
        else:
            detail.append(f"{process_name}({current:.2f}{unit})")

    is_static = str(threshold_type).lower() == "static"
    prefix = f"{label}触发静态阈值告警，新增异常进程：" if is_static else f"{label}触发动态阈值告警，新增异常进程："
    reason = prefix + "，".join(detail) + f"。共 {len(processes)} 个。"

    if not is_static and sorted_processes:
        strongest = max(
            sorted_processes,
            key=lambda item: (state_rank(str(item.get("state") or "NORMAL")), float(item.get("score") or 0.0)),
        )
        dynamic_meta = strongest.get("dynamic_threshold") if isinstance(strongest, dict) else {}
        if not isinstance(dynamic_meta, dict):
            dynamic_meta = {}
        detector_scores = dynamic_meta.get("detector_scores") or {}
        detector_weights = dynamic_meta.get("detector_weights") or {}
        final_score = _safe_float(dynamic_meta.get("final_score"), 0.0)
        if final_score > 0:
            reason = (
                f"{reason} Dynamic fusion final_score={final_score:.3f}, "
                f"detector(spike/sustained/trend)="
                f"{_safe_float((detector_scores or {}).get('spike')):.3f}/"
                f"{_safe_float((detector_scores or {}).get('sustained')):.3f}/"
                f"{_safe_float((detector_scores or {}).get('trend')):.3f}, "
                f"weights="
                f"{_safe_float((detector_weights or {}).get('spike'), 0.5):.2f}/"
                f"{_safe_float((detector_weights or {}).get('sustained'), 0.3):.2f}/"
                f"{_safe_float((detector_weights or {}).get('trend'), 0.2):.2f}."
            )

    if suppressed_count > 0:
        reason = f"{reason} 已抑制 {suppressed_count} 个重复进程告警。"
    return reason


def _filter_grouped_process_signal_for_emit(
    feature_name: str,
    signal: Dict[str, object],
    state: DetectionState,
    suppression_seconds: int,
    now: datetime,
) -> Tuple[Optional[Dict[str, object]], List[str], int]:
    if not _is_grouped_process_feature(feature_name):
        return signal, [], 0

    processes_raw = signal.get("processes") or []
    if not isinstance(processes_raw, list) or not processes_raw:
        return signal, [], 0

    threshold_type = str(signal.get("threshold_type") or "").strip().lower()
    valid_processes = [item for item in processes_raw if isinstance(item, dict)]
    if not valid_processes:
        return signal, [], 0

    same_type = [
        item
        for item in valid_processes
        if not threshold_type or str(item.get("threshold_type") or "").strip().lower() == threshold_type
    ]
    candidate_processes = same_type or valid_processes

    emitted_keys: List[str] = []
    new_processes: List[Dict[str, object]] = []
    suppressed_count = 0
    for item in candidate_processes:
        process_name = str(item.get("process_name") or "").strip()
        if not process_name:
            continue
        dedup_key = f"{feature_name}:{process_name.lower()}"
        last_emit_at = state.process_last_emit_at.get(dedup_key)
        if last_emit_at is not None and int((now - last_emit_at).total_seconds()) < suppression_seconds:
            suppressed_count += 1
            continue
        new_processes.append(item)
        emitted_keys.append(dedup_key)

    if not new_processes:
        return None, [], suppressed_count

    worst = max(
        new_processes,
        key=lambda item: (state_rank(str(item.get("state") or "NORMAL")), float(item.get("score") or 0.0)),
    )
    filtered_signal = dict(signal)
    filtered_signal["processes"] = new_processes
    filtered_signal["process_count"] = len(new_processes)
    filtered_signal["current"] = worst.get("current")
    filtered_signal["mean"] = worst.get("mean")
    filtered_signal["std"] = worst.get("std")
    filtered_signal["trend"] = worst.get("trend")
    filtered_signal["state"] = worst.get("state") or signal.get("state") or "WARNING"
    filtered_signal["level"] = worst.get("level") or signal.get("level") or state_to_level(str(filtered_signal["state"]))
    filtered_signal["score"] = worst.get("score") or signal.get("score") or 1.0
    filtered_signal["reasons"] = [
        _build_grouped_process_reason(feature_name, threshold_type or str(signal.get("threshold_type") or ""), new_processes, suppressed_count)
    ]
    return filtered_signal, emitted_keys, suppressed_count


def _filter_grouped_process_context_for_emit(
    context: Dict[str, object],
    state: DetectionState,
    suppression_seconds: int,
    now: datetime,
) -> Tuple[Optional[Dict[str, object]], List[str], int]:
    top_feature = str(context.get("top_feature") or "")
    if not _is_grouped_process_feature(top_feature):
        return context, [], 0

    top_signal = context.get("top_signal") or {}
    if not isinstance(top_signal, dict):
        return context, [], 0

    filtered_signal, dedup_keys, suppressed_count = _filter_grouped_process_signal_for_emit(
        top_feature,
        top_signal,
        state,
        suppression_seconds,
        now,
    )
    if filtered_signal is None:
        return None, [], suppressed_count

    filtered_context = dict(context)
    filtered_context["top_signal"] = filtered_signal
    filtered_context["top_value"] = filtered_signal.get("current")
    filtered_context["top_mean"] = filtered_signal.get("mean")
    filtered_context["top_std"] = filtered_signal.get("std")
    filtered_context["top_trend"] = filtered_signal.get("trend")
    filtered_context["top_threshold_type"] = filtered_signal.get("threshold_type")
    filtered_context["top_threshold_value"] = filtered_signal.get("threshold_value")
    filtered_context["top_level"] = str(filtered_signal.get("level") or filtered_context.get("top_level") or "warning")
    filtered_context["reason"] = "; ".join(str(item) for item in (filtered_signal.get("reasons") or []) if str(item).strip())
    if filtered_signal.get("recommendation"):
        filtered_context["recommendation"] = filtered_signal.get("recommendation")

    feature_signals = dict(filtered_context.get("feature_signals") or {})
    feature_signals[top_feature] = filtered_signal
    filtered_context["feature_signals"] = feature_signals
    return filtered_context, dedup_keys, suppressed_count


def _emit_firing_alert(
    target: TargetDescriptor,
    config: dict,
    state: DetectionState,
    context: Dict[str, object],
    decision: Dict[str, object],
) -> None:
    top_signal = context.get("top_signal") or {}
    fingerprint = _build_fingerprint(target, context)
    alert = build_alert(
        target.display_name,
        str(config.get("rule_name", "AI intelligent alert")),
        float(context.get("top_value") or 0.0),
        str(decision.get("reason") or context.get("reason") or "检测到潜在异常。"),
        "firing",
        level=str(decision.get("level") or state_to_level(str(context.get("current_state")))),
        recommendation=str(decision.get("recommendation") or context.get("recommendation") or ""),
        decision_source=str(decision.get("decision_source") or "heuristic"),
        duration_seconds=int(context.get("duration_seconds") or 0),
        confidence_score=float(context.get("confidence") or 0.0),
        fingerprint=fingerprint,
        context=context,
        metric_name=str(context.get("top_feature") or ""),
        alert_state=str(context.get("current_state") or "NORMAL"),
        previous_state=str(context.get("previous_state") or "NORMAL"),
        threshold_type=context.get("top_threshold_type"),
        threshold_value=context.get("top_threshold_value"),
        static_threshold=(top_signal.get("static_threshold") or {}).get("threshold"),
        dynamic_threshold=(top_signal.get("dynamic_threshold") or {}).get("threshold"),
        mean_value=context.get("top_mean"),
        std_value=context.get("top_std"),
        trend_value=context.get("top_trend"),
    )
    emit_alert(alert, config)
    now = datetime.now(timezone.utc)
    state.active_alert = True
    state.active_fingerprint = fingerprint
    state.last_notified_fingerprint = fingerprint
    state.last_emit_at = now
    state.last_context = context


def _emit_feature_firing_alert(
    target: TargetDescriptor,
    config: dict,
    context: Dict[str, object],
    decision: Dict[str, object],
) -> str:
    top_signal = context.get("top_signal") or {}
    fingerprint = _build_fingerprint(target, context)
    alert = build_alert(
        target.display_name,
        str(config.get("rule_name", "AI intelligent alert")),
        float(context.get("top_value") or 0.0),
        str(decision.get("reason") or context.get("reason") or "检测到潜在异常。"),
        "firing",
        level=str(decision.get("level") or state_to_level(str(context.get("current_state")))),
        recommendation=str(decision.get("recommendation") or context.get("recommendation") or ""),
        decision_source=str(decision.get("decision_source") or "heuristic"),
        duration_seconds=int(context.get("duration_seconds") or 0),
        confidence_score=float(context.get("confidence") or 0.0),
        fingerprint=fingerprint,
        context=context,
        metric_name=str(context.get("top_feature") or ""),
        alert_state=str(context.get("current_state") or "NORMAL"),
        previous_state=str(context.get("previous_state") or "NORMAL"),
        threshold_type=context.get("top_threshold_type"),
        threshold_value=context.get("top_threshold_value"),
        static_threshold=(top_signal.get("static_threshold") or {}).get("threshold"),
        dynamic_threshold=(top_signal.get("dynamic_threshold") or {}).get("threshold"),
        mean_value=context.get("top_mean"),
        std_value=context.get("top_std"),
        trend_value=context.get("top_trend"),
    )
    emit_alert(alert, config)
    return fingerprint


def _emit_feature_resolved_alert(
    target: TargetDescriptor,
    config: dict,
    feature_name: str,
    previous_context: Optional[Dict[str, object]],
    fingerprint: Optional[str],
    from_state: str,
    to_state: str,
) -> None:
    base_context = dict(previous_context or {})
    if not base_context:
        base_context = {
            "host": target.display_name,
            "target_role": target.role,
            "query_host": target.resolved_query_host(),
            "top_feature": feature_name,
            "top_threshold_type": "dynamic",
        }
    top_signal = base_context.get("top_signal") or {}
    message = (
        f"动态阈值状态由 {from_state} 迁移到 {to_state}，恢复条件已满足。"
    )
    recommendation = "建议继续观察指标趋势，确认负载稳定。"
    alert = build_alert(
        target.display_name,
        str(config.get("rule_name", "AI intelligent alert")),
        float(base_context.get("top_value") or 0.0),
        message,
        "resolved",
        level=state_to_level(from_state),
        recommendation=recommendation,
        decision_source="state_machine",
        duration_seconds=int(base_context.get("duration_seconds") or 0),
        confidence_score=float(base_context.get("confidence") or 0.0),
        fingerprint=fingerprint,
        context={**base_context, "current_state": to_state, "previous_state": from_state},
        metric_name=str(base_context.get("top_feature") or feature_name),
        alert_state=to_state,
        previous_state=from_state,
        threshold_type=base_context.get("top_threshold_type"),
        threshold_value=base_context.get("top_threshold_value"),
        static_threshold=(top_signal.get("static_threshold") or {}).get("threshold"),
        dynamic_threshold=(top_signal.get("dynamic_threshold") or {}).get("threshold"),
        mean_value=base_context.get("top_mean"),
        std_value=base_context.get("top_std"),
        trend_value=base_context.get("top_trend"),
    )
    emit_alert(alert, config)


def _emit_dynamic_feature_alerts(
    target: TargetDescriptor,
    config: dict,
    state: DetectionState,
    detection: Dict[str, object],
    feedback_profiles: Dict[str, object],
) -> None:
    suppression_seconds = int((config.get("dedup") or {}).get("suppression_seconds", 300))
    feature_signals = detection.get("feature_signals") or {}
    known_features = set(feature_signals.keys()) | set(state.dynamic_feature_machines.keys())

    for feature_name in sorted(known_features):
        signal = feature_signals.get(feature_name) or {}
        machine = _get_dynamic_feature_machine(state, feature_name)
        feature_detection = _build_dynamic_feature_detection(feature_name, signal)
        now = datetime.now(timezone.utc)
        transition = apply_state_machine(machine, feature_detection, config, now)
        print(
            f"[state][dynamic] target={target.key} feature={feature_name} "
            f"from={transition.from_state} to={transition.to_state} changed={transition.changed} "
            f"reason={transition.reason}"
        )

        if not transition.changed:
            continue

        if transition.to_state == "NORMAL":
            active_fingerprint = state.dynamic_feature_active_fingerprint.get(feature_name)
            previous_context = state.dynamic_feature_last_context.get(feature_name)
            if active_fingerprint and previous_context:
                _emit_feature_resolved_alert(
                    target,
                    config,
                    feature_name,
                    previous_context,
                    active_fingerprint,
                    transition.from_state,
                    transition.to_state,
                )
            state.dynamic_feature_active_fingerprint.pop(feature_name, None)
            state.dynamic_feature_last_context.pop(feature_name, None)
            continue

        if not signal:
            continue

        context = _build_feature_context(
            target,
            config,
            detection,
            feedback_profiles,
            machine,
            feature_name,
            signal_override=signal,
        )
        context["anomaly"] = True
        context["anomaly_by_fixed_threshold"] = False
        context["anomaly_by_dynamic_threshold"] = True
        context["current_state"] = transition.to_state
        context["previous_state"] = transition.from_state
        context["state_changed"] = True
        context["top_level"] = state_to_level(transition.to_state)
        context["top_threshold_type"] = "dynamic"
        if not str(context.get("reason") or "").strip():
            context["reason"] = f"{feature_name} 触发动态阈值状态迁移。"

        context_for_emit, process_dedup_keys, suppressed_count = _filter_grouped_process_context_for_emit(
            context,
            state,
            suppression_seconds,
            now,
        )
        if context_for_emit is None:
            print(
                f"[alert] target={target.key} feature={feature_name} grouped-process alert suppressed by dedup "
                f"(count={suppressed_count})"
            )
            continue

        decision = decide_alert(context_for_emit, config)
        if not decision.get("needs_alert"):
            print(f"[alert] target={target.key} feature={feature_name} decision engine rejected alert")
            continue

        transition_key = f"{target.key}:{feature_name}:{transition.from_state}->{transition.to_state}"
        transition_last_emit_at = state.dynamic_feature_transition_last_emit_at.get(transition_key)
        if (
            transition_last_emit_at is not None
            and int((now - transition_last_emit_at).total_seconds()) < suppression_seconds
        ):
            print(
                f"[alert] target={target.key} feature={feature_name} "
                "dynamic state-transition alert suppressed by dedup/suppression window"
            )
            continue

        if (
            state_rank(transition.to_state) < state_rank(transition.from_state)
            and transition.to_state != "NORMAL"
        ):
            active_fingerprint = state.dynamic_feature_active_fingerprint.get(feature_name)
            previous_context = state.dynamic_feature_last_context.get(feature_name)
            if active_fingerprint and previous_context:
                _emit_feature_resolved_alert(
                    target,
                    config,
                    feature_name,
                    previous_context,
                    active_fingerprint,
                    transition.from_state,
                    transition.to_state,
                )

        print(
            f"[alert] target={target.key} feature={feature_name} emit dynamic firing "
            f"level={decision.get('level')} flow={transition.from_state}->{transition.to_state}"
        )
        fingerprint = _emit_feature_firing_alert(target, config, context_for_emit, decision)
        state.dynamic_feature_active_fingerprint[feature_name] = fingerprint
        state.dynamic_feature_last_context[feature_name] = context_for_emit
        state.dynamic_feature_transition_last_emit_at[transition_key] = now
        for process_dedup_key in process_dedup_keys:
            state.process_last_emit_at[process_dedup_key] = now

def _emit_static_metric_alerts(
    target: TargetDescriptor,
    config: dict,
    state: DetectionState,
    detection: Dict[str, object],
    feedback_profiles: Dict[str, object],
) -> bool:
    suppression_seconds = int((config.get("dedup") or {}).get("suppression_seconds", 300))
    emitted = False
    feature_signals = detection.get("feature_signals") or {}

    for feature_name in detection.get("fixed_triggered_features") or []:
        signal = feature_signals.get(feature_name) or {}
        if str(signal.get("threshold_type") or "").lower() != "static":
            continue

        now = datetime.now(timezone.utc)
        signal_for_emit = signal
        process_dedup_keys: List[str] = []
        if _is_grouped_process_feature(feature_name):
            signal_for_emit, process_dedup_keys, suppressed_count = _filter_grouped_process_signal_for_emit(
                feature_name,
                signal,
                state,
                suppression_seconds,
                now,
            )
            if signal_for_emit is None:
                print(
                    f"[alert] target={target.key} feature={feature_name} "
                    f"all processes suppressed by dedup window (count={suppressed_count})"
                )
                continue

        context = _build_feature_context(
            target,
            config,
            detection,
            feedback_profiles,
            state,
            feature_name,
            signal_override=signal_for_emit,
        )
        previous_feature_state = _normalize_state(state.static_feature_states.get(feature_name), "NORMAL")
        static_signal = signal_for_emit.get("static_threshold") or {}
        current_feature_state = _normalize_state(
            static_signal.get("state") or signal_for_emit.get("state") or "WARNING",
            "WARNING",
        )
        feature_state_changed = current_feature_state != previous_feature_state
        context["current_state"] = current_feature_state
        context["previous_state"] = previous_feature_state
        context["state_changed"] = feature_state_changed
        context["top_level"] = state_to_level(current_feature_state)

        fingerprint = _build_fingerprint(target, context)
        last_emit_at = state.feature_last_emit_at.get(fingerprint)
        transition_key = (
            f"{target.key}:{feature_name}:{previous_feature_state}->{current_feature_state}"
        )
        if feature_state_changed:
            transition_last_emit_at = state.feature_transition_last_emit_at.get(transition_key)
            if (
                transition_last_emit_at is not None
                and int((now - transition_last_emit_at).total_seconds()) < suppression_seconds
            ):
                print(
                    f"[alert] target={target.key} feature={feature_name} "
                    "state-transition alert suppressed by dedup/suppression window"
                )
                state.static_feature_states[feature_name] = current_feature_state
                continue
        elif (
            last_emit_at is not None
            and int((now - last_emit_at).total_seconds()) < suppression_seconds
        ):
            print(f"[alert] target={target.key} feature={feature_name} suppressed by dedup/suppression window")
            state.static_feature_states[feature_name] = current_feature_state
            continue

        decision = {
            "needs_alert": True,
            "level": str(context.get("top_level") or state_to_level(current_feature_state)),
            "reason": str(context.get("reason") or "检测到静态阈值异常。"),
            "recommendation": str(context.get("recommendation") or ""),
            "decision_source": "heuristic",
        }
        print(
            f"[alert] target={target.key} feature={feature_name} emit static firing "
            f"level={decision.get('level')} flow={previous_feature_state}->{current_feature_state}"
        )
        _emit_firing_alert(target, config, state, context, decision)
        state.feature_last_emit_at[fingerprint] = now
        if feature_state_changed:
            state.feature_transition_last_emit_at[transition_key] = now
        state.static_feature_states[feature_name] = current_feature_state
        for process_dedup_key in process_dedup_keys:
            state.process_last_emit_at[process_dedup_key] = now
        emitted = True

    known_static_features = set(state.static_feature_states.keys()) | set(feature_signals.keys())
    for feature_name in known_static_features:
        signal = feature_signals.get(feature_name) or {}
        static_signal = signal.get("static_threshold") or {}
        threshold_type = str(signal.get("threshold_type") or "").lower()
        if threshold_type == "static" and bool(static_signal.get("breached")):
            state.static_feature_states[feature_name] = _normalize_state(
                static_signal.get("state") or signal.get("state") or "WARNING",
                "WARNING",
            )
        else:
            state.static_feature_states.pop(feature_name, None)

    return emitted


def _emit_resolved_alert(target: TargetDescriptor, config: dict, state: DetectionState) -> None:
    context = state.last_context or {"host": target.display_name}
    top_signal = context.get("top_signal") or {}
    alert = build_alert(
        target.display_name,
        str(config.get("rule_name", "AI intelligent alert")),
        float(context.get("top_value") or 0.0),
        "监控指标已恢复到基线范围，状态机已回到 NORMAL。",
        "resolved",
        level=state_to_level(str(context.get("previous_state") or "WARNING")),
        recommendation="建议继续观察恢复后趋势，确认稳定且未再次波动。",
        decision_source="state_machine",
        duration_seconds=int(context.get("duration_seconds") or 0),
        confidence_score=float(context.get("confidence") or 0.0),
        fingerprint=state.active_fingerprint,
        context={**context, "current_state": "NORMAL"},
        metric_name=str(context.get("top_feature") or ""),
        alert_state="NORMAL",
        previous_state=str(context.get("current_state") or state.previous_state or "WARNING"),
        threshold_type=context.get("top_threshold_type"),
        threshold_value=context.get("top_threshold_value"),
        static_threshold=(top_signal.get("static_threshold") or {}).get("threshold"),
        dynamic_threshold=(top_signal.get("dynamic_threshold") or {}).get("threshold"),
        mean_value=context.get("top_mean"),
        std_value=context.get("top_std"),
        trend_value=context.get("top_trend"),
    )
    emit_alert(alert, config)
    state.active_alert = False
    state.active_fingerprint = None
    state.last_notified_fingerprint = f"{target.key}:{context.get('top_feature') or 'unknown'}:NORMAL"
    state.last_emit_at = datetime.now(timezone.utc)
    state.last_context = None


def _emit_resolved_alert_on_downgrade(
    target: TargetDescriptor,
    config: dict,
    state: DetectionState,
    to_state: str,
) -> None:
    context = state.last_context or {"host": target.display_name}
    top_signal = context.get("top_signal") or {}
    from_state = str(context.get("current_state") or state.previous_state or "WARNING")
    next_state = str(to_state or "WARNING")
    alert = build_alert(
        target.display_name,
        str(config.get("rule_name", "AI intelligent alert")),
        float(context.get("top_value") or 0.0),
        f"告警级别已从 {from_state} 下调到 {next_state}，高等级告警条件已恢复。",
        "resolved",
        level=state_to_level(from_state),
        recommendation="建议继续观察指标变化，如再次持续升高可重新升级告警。",
        decision_source="state_machine",
        duration_seconds=int(context.get("duration_seconds") or 0),
        confidence_score=float(context.get("confidence") or 0.0),
        fingerprint=state.active_fingerprint,
        context={**context, "current_state": next_state},
        metric_name=str(context.get("top_feature") or ""),
        alert_state=next_state,
        previous_state=from_state,
        threshold_type=context.get("top_threshold_type"),
        threshold_value=context.get("top_threshold_value"),
        static_threshold=(top_signal.get("static_threshold") or {}).get("threshold"),
        dynamic_threshold=(top_signal.get("dynamic_threshold") or {}).get("threshold"),
        mean_value=context.get("top_mean"),
        std_value=context.get("top_std"),
        trend_value=context.get("top_trend"),
    )
    emit_alert(alert, config)
    state.active_alert = False
    state.active_fingerprint = None
    state.last_notified_fingerprint = f"{target.key}:{context.get('top_feature') or 'unknown'}:{next_state}"
    state.last_emit_at = datetime.now(timezone.utc)
    state.last_context = None


def run_detection(target: TargetDescriptor, config: dict, state: DetectionState) -> DetectionState:
    influx = config["influx"]
    model_cfg = config["model"]
    alert_cfg = config["alert"]
    metric_names = list(model_cfg["features"]) + ["abnormal_process"]
    feedback_profiles = fetch_feedback_profiles(
        target.display_name,
        str(config.get("rule_name", "AI intelligent alert")),
        metric_names,
        config,
    )
    runtime_fixed_rules = fetch_runtime_static_rules(target.display_name, config)

    with InfluxDBClient(url=influx["url"], token=influx["token"], org=influx["org"]) as client:
        queries = build_queries(target, int(alert_cfg["history_minutes"]))
        df = query_metrics(client, influx["bucket"], f"-{alert_cfg['history_minutes']}m", queries)
        if df.empty:
            print(f"[detect] target={target.key} no data in last {alert_cfg['history_minutes']} minutes")
            return state

        df = df.drop(columns=["_time"], errors="ignore").dropna()
        if len(df) < alert_cfg["min_points"]:
            print(f"[detect] target={target.key} insufficient data: rows={len(df)} required={alert_cfg['min_points']}")
            return state

        series = df[model_cfg["features"]].to_numpy(dtype=np.float32)
        model = load_or_train_model(series, config, target)
        runtime_config = dict(config)
        runtime_config["fixed_thresholds"] = runtime_fixed_rules
        detection = detect_anomaly(
            model,
            series,
            min(int(model_cfg["window_size"]), len(series)),
            model_cfg["features"],
            runtime_config,
            feedback_profiles=feedback_profiles,
        )
        process_signal = detect_process_anomaly(
            client,
            influx["bucket"],
            target.display_name,
            target.resolved_query_host(),
            target.role,
            int(alert_cfg["history_minutes"]),
            config,
            feedback_profiles.get("abnormal_process"),
            runtime_fixed_rules,
        )
        detection = _merge_process_signal(detection, process_signal)
        _print_detection_log(target, detection, state, len(df), int(alert_cfg["min_points"]))

        if detection.get("fixed_triggered_features"):
            _emit_static_metric_alerts(
                target,
                config,
                state,
                detection,
                feedback_profiles,
            )
        _emit_dynamic_feature_alerts(target, config, state, detection, feedback_profiles)

    return state


def run_train(target: TargetDescriptor, config: dict):
    if not config.get("prediction", {}).get("enabled", True):
        return

    influx = config["influx"]
    model_cfg = config["model"]
    alert_cfg = config["alert"]

    with InfluxDBClient(url=influx["url"], token=influx["token"], org=influx["org"]) as client:
        queries = build_queries(target, int(alert_cfg["history_minutes"]))
        df = query_metrics(client, influx["bucket"], f"-{alert_cfg['history_minutes']}m", queries)
        if df.empty:
            return
        df = df.drop(columns=["_time"], errors="ignore").dropna()
        if len(df) < alert_cfg["min_points"]:
            return
        series = df[model_cfg["features"]].to_numpy(dtype=np.float32)
        model = train_model(
            series,
            model_cfg["window_size"],
            model_cfg["lstm_units"],
            model_cfg["epochs"],
            model_cfg["batch_size"],
        )
        if model:
            path = _target_model_path(config, target)
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            model.save(path)


def _single_target(host: str) -> TargetDescriptor:
    return TargetDescriptor(host=host, role="host", query_host=host, parent_host="")


def _resolve_targets(single_host: str, config: dict) -> list[TargetDescriptor]:
    if single_host:
        return [_single_target(single_host)]

    runtime_targets = fetch_runtime_targets(config)
    return [
        TargetDescriptor(
            host=str(item.get("host") or "").strip(),
            role=str(item.get("role") or "host").strip().lower() or "host",
            query_host=str(item.get("query_host") or item.get("queryHost") or item.get("host") or "").strip(),
            parent_host=str(item.get("parent_host") or item.get("parentHost") or "").strip(),
        )
        for item in runtime_targets
        if str(item.get("host") or "").strip()
    ]


def loop(host: str, config_path: str):
    config = load_config(config_path)
    interval = int(config["alert"]["interval_seconds"])
    train_time = config["alert"].get("train_time")
    last_train_date: Dict[str, object] = {}
    states: Dict[str, DetectionState] = {}

    while True:
        now = datetime.now()
        targets = _resolve_targets(host, config)
        if not targets:
            print("[loop] no runtime targets discovered")

        for target in targets:
            state = states.get(target.key, DetectionState())
            states[target.key] = run_detection(target, config, state)

            if train_time and now.strftime("%H:%M") == train_time:
                if last_train_date.get(target.key) != now.date():
                    run_train(target, config)
                    last_train_date[target.key] = now.date()

        known_keys = {target.key for target in targets}
        stale_keys = [key for key in states.keys() if key not in known_keys and not host]
        for key in stale_keys:
            states.pop(key, None)
            last_train_date.pop(key, None)

        time.sleep(interval)

