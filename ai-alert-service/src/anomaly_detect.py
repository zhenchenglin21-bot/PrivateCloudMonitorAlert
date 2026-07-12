from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from .state_machine import state_rank, state_to_level
from .utils import zscore


FEATURE_LABELS = {
    "cpu": "CPU用户态占比",
    "mem": "内存使用率",
    "disk": "磁盘使用率",
    "net_in": "网络入流量",
    "net_out": "网络出流量",
    "abnormal_process": "异常进程",
    "abnormal_process_cpu": "异常进程CPU",
    "abnormal_process_mem": "异常进程内存",
}


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _clamp(value: float, lower: float, upper: float) -> float:
    return max(lower, min(upper, value))


def _feature_label(feature: str) -> str:
    return FEATURE_LABELS.get(feature, feature)


def _build_recommendation(feature: str, threshold_type: Optional[str], rule_name: Optional[str] = None) -> str:
    feature_label = _feature_label(feature)
    if threshold_type == "static":
        if rule_name:
            return f"优先按静态规则“{rule_name}”排查，重点确认{feature_label}持续越阈的根因。"
        return f"优先检查{feature_label}的静态阈值配置与当前运行负载，确认是否存在持续超限。"
    if threshold_type == "dynamic":
        return f"建议结合近期负载变化与基线漂移排查{feature_label}异常，必要时再微调动态参数。"
    return "建议结合系统日志、进程行为和近期发布变更进行关联排查。"


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


def _resolve_static_severity(
    feature: str,
    fallback_severity: str,
    breach_ratio: float,
    static_policy_cfg: Dict[str, Any],
) -> str:
    feature_policy = static_policy_cfg.get(feature) or {}
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


def _choose_window(values: np.ndarray, dynamic_cfg: Dict[str, Any]) -> np.ndarray:
    window_points = max(5, int(dynamic_cfg.get("window_points", min(len(values), 30))))
    return values[-window_points:]


def _evaluate_fixed_threshold(
    feature: str,
    current: float,
    fixed_cfg: Dict[str, Any],
    feedback_profile: Dict[str, Any],
    static_policy_cfg: Dict[str, Any],
) -> Dict[str, Any]:
    rules = fixed_cfg.get(feature) or []
    if isinstance(rules, dict):
        rules = [rules]

    offset_pct = _safe_float(feedback_profile.get("fixed_threshold_offset_pct"), 0.0)
    matched_rule = None
    matched_message = None
    matched_direction = None
    matched_threshold = None
    matched_state = "NORMAL"
    matched_severity = "warning"
    matched_upper = None
    matched_lower = None

    for rule in rules:
        upper = rule.get("upper")
        lower = rule.get("lower")
        severity = str(rule.get("severity", "warning")).lower()
        adjusted_upper = None if upper is None else _safe_float(upper) * (1.0 + offset_pct)
        adjusted_lower = None if lower is None else _safe_float(lower) * (1.0 - offset_pct)

        breached = False
        direction = None
        threshold_value = None
        if adjusted_upper is not None and current > adjusted_upper:
            breached = True
            direction = "upper"
            threshold_value = adjusted_upper
        elif adjusted_lower is not None and current < adjusted_lower:
            breached = True
            direction = "lower"
            threshold_value = adjusted_lower

        if not breached:
            continue

        threshold_base = max(abs(_safe_float(threshold_value, 0.0)), 1.0)
        breach_ratio = abs(current - _safe_float(threshold_value, current)) / threshold_base
        explicit_severity = str(rule.get("severity") or "").strip()
        if explicit_severity:
            resolved_severity = _normalize_severity_label(explicit_severity)
        else:
            resolved_severity = _resolve_static_severity(feature, severity, breach_ratio, static_policy_cfg)
        candidate_state = _severity_to_state(resolved_severity)

        if matched_rule is None or state_rank(candidate_state) > state_rank(matched_state):
            matched_rule = rule
            matched_state = candidate_state
            matched_severity = resolved_severity
            matched_direction = direction
            matched_threshold = threshold_value
            matched_upper = adjusted_upper
            matched_lower = adjusted_lower
            rule_name = str(rule.get("name") or feature)
            if direction == "upper":
                matched_message = (
                    f"{_feature_label(feature)}触发静态阈值告警（规则：{rule_name}）："
                    f"当前值={current:.3f}，阈值={threshold_value:.3f}。"
                )
            else:
                matched_message = (
                    f"{_feature_label(feature)}触发静态阈值告警（规则：{rule_name}）："
                    f"当前值={current:.3f}，下限阈值={threshold_value:.3f}。"
                )

    return {
        "breached": matched_rule is not None,
        "severity": "warning" if matched_rule is None else matched_severity,
        "state": matched_state,
        "direction": matched_direction,
        "threshold": matched_threshold,
        "upper": matched_upper,
        "lower": matched_lower,
        "rule_name": None if matched_rule is None else matched_rule.get("name"),
        "message": matched_message,
    }


def _ema_stats(recent: np.ndarray, alpha: float) -> Tuple[float, float]:
    if recent.size == 0:
        return 0.0, 0.0
    mean_value = float(recent[0])
    std_value = 0.0
    for item in recent[1:]:
        value = float(item)
        mean_value = alpha * value + (1.0 - alpha) * mean_value
        std_value = alpha * abs(value - mean_value) + (1.0 - alpha) * std_value
    return mean_value, std_value


def _normalize_detector_weights(weights: Dict[str, float]) -> Dict[str, float]:
    normalized: Dict[str, float] = {}
    total = 0.0
    for key in ("spike", "sustained", "trend"):
        value = max(0.0, _safe_float(weights.get(key), 0.0))
        normalized[key] = value
        total += value
    if total <= 1e-9:
        return {"spike": 0.5, "sustained": 0.3, "trend": 0.2}
    return {key: value / total for key, value in normalized.items()}


def _score_to_state(final_score: float, warning: float, alert: float, critical: float) -> str:
    if final_score >= critical:
        return "CRITICAL"
    if final_score >= alert:
        return "ALERT"
    if final_score >= warning:
        return "WARNING"
    return "NORMAL"


def _evaluate_dynamic_threshold(
    feature: str,
    recent: np.ndarray,
    values: np.ndarray,
    current: float,
    dynamic_cfg: Dict[str, Any],
    z_cfg: Dict[str, Any],
    feedback_profile: Dict[str, Any],
) -> Dict[str, Any]:
    if not dynamic_cfg.get("enabled", True) or recent.size == 0:
        return {
            "breached": False,
            "trend_breached": False,
            "mean": 0.0,
            "std": 0.0,
            "trend": 0.0,
            "adjusted_value": current,
            "warning_threshold": None,
            "alert_threshold": None,
            "high_threshold": None,
            "threshold": None,
            "state": "NORMAL",
            "anomaly_level": "NORMAL",
            "warning_k": None,
            "alert_k": None,
            "beta": None,
            "ema_alpha": None,
            "z_score": 0.0,
            "detector_scores": {"spike": 0.0, "sustained": 0.0, "trend": 0.0},
            "detector_weights": {"spike": 0.5, "sustained": 0.3, "trend": 0.2},
            "final_score": 0.0,
            "warning_score_threshold": None,
            "alert_score_threshold": None,
            "critical_score_threshold": None,
            "trend_slope": 0.0,
            "trend_slope_sigma": 0.0,
            "trend_r2": 0.0,
            "sustained_baseline": None,
            "sustained_over_count": 0,
            "sustained_tail_count": 0,
            "message": None,
        }

    alpha = _clamp(_safe_float(dynamic_cfg.get("ema_alpha", 0.3)), 0.05, 0.95)
    mean_value, std_value = _ema_stats(recent, alpha)
    std_floor_abs = max(0.0, _safe_float(dynamic_cfg.get("std_floor_abs", 1.0), 1.0))
    std_floor_ratio = max(0.0, _safe_float(dynamic_cfg.get("std_floor_ratio", 0.02), 0.02))
    effective_std = max(std_value, std_floor_abs, abs(mean_value) * std_floor_ratio)

    base_warning_k = _safe_float(dynamic_cfg.get("warning_k", 1.5))
    base_alert_k = _safe_float(dynamic_cfg.get("alert_k", 2.5))
    legacy_k_adj = _safe_float(feedback_profile.get("dynamic_k_adjustment"), 0.0)
    warning_k_adj = _safe_float(feedback_profile.get("dynamic_warning_k_adjustment"), legacy_k_adj)
    alert_k_adj = _safe_float(feedback_profile.get("dynamic_alert_k_adjustment"), legacy_k_adj)
    warning_k = _clamp(
        base_warning_k + warning_k_adj,
        _safe_float(dynamic_cfg.get("warning_k_min", 1.0)),
        _safe_float(dynamic_cfg.get("warning_k_max", 3.0)),
    )
    alert_k = _clamp(
        max(base_alert_k + alert_k_adj, warning_k + 0.1),
        _safe_float(dynamic_cfg.get("alert_k_min", 1.5)),
        _safe_float(dynamic_cfg.get("alert_k_max", 4.0)),
    )

    base_beta = _safe_float(dynamic_cfg.get("trend_beta", 0.2))
    beta_adj = _safe_float(feedback_profile.get("trend_beta_adjustment"), 0.0)
    beta = _clamp(
        base_beta + beta_adj,
        _safe_float(dynamic_cfg.get("trend_beta_min", 0.0)),
        _safe_float(dynamic_cfg.get("trend_beta_max", 1.0)),
    )

    prev_value = float(values[-2]) if values.size >= 2 else current
    trend_value = current - prev_value
    trend_cap_sigma = max(0.0, _safe_float(dynamic_cfg.get("trend_cap_sigma", 3.0), 3.0))
    trend_cap_abs = max(0.0, _safe_float(dynamic_cfg.get("trend_cap_abs", effective_std), effective_std))
    trend_cap = max(trend_cap_abs, effective_std * trend_cap_sigma)
    clamped_trend = _clamp(trend_value, -trend_cap, trend_cap)
    adjusted_value = current + beta * clamped_trend

    # Detector 1: statistical-distribution detector.
    warning_threshold = mean_value + warning_k * effective_std
    alert_threshold = mean_value + alert_k * effective_std
    z_result = zscore(adjusted_value, mean_value, effective_std, _safe_float(z_cfg.get("threshold"), 2.5))
    spike_critical_margin = _safe_float(dynamic_cfg.get("spike_critical_margin", 0.8), 0.8)
    spike_critical_z = max(warning_k + 0.5, alert_k + spike_critical_margin)
    spike_score = _clamp(
        (z_result.score - warning_k) / max(spike_critical_z - warning_k, 1e-6),
        0.0,
        1.0,
    )

    # Detector 2: sustained detector.
    sustained_window_points = max(3, int(dynamic_cfg.get("sustained_window_points", 8)))
    sustained_window = recent[-min(recent.size, sustained_window_points):]
    sustained_baseline_k = _safe_float(dynamic_cfg.get("sustained_baseline_k", 0.6), 0.6)
    sustained_baseline = mean_value + sustained_baseline_k * effective_std
    absolute_floor_cfg = dynamic_cfg.get("absolute_load_floor") or {}
    absolute_floor = _safe_float(absolute_floor_cfg.get(feature), -1.0)
    if absolute_floor > 0:
        # For high-importance utilization metrics (for example CPU), ensure sustained detector
        # can still trigger under long high-load plateaus even when EMA mean is already elevated.
        sustained_baseline = min(sustained_baseline, absolute_floor)
    sustained_hits_base = max(2, int(dynamic_cfg.get("sustained_required_hits", max(3, int(np.ceil(sustained_window_points * 0.6))))))
    sustained_hits_adj = int(round(_safe_float(feedback_profile.get("dynamic_sustained_hits_adjustment"), 0.0)))
    sustained_required_hits = int(
        _clamp(
            sustained_hits_base + sustained_hits_adj,
            2.0,
            float(max(2, sustained_window_points)),
        )
    )
    over_mask = sustained_window > sustained_baseline
    sustained_over_count = int(np.sum(over_mask))
    sustained_tail_count = 0
    for value in reversed(sustained_window.tolist()):
        if float(value) > sustained_baseline:
            sustained_tail_count += 1
        else:
            break
    sustained_ratio_score = _clamp(sustained_over_count / max(sustained_required_hits, 1), 0.0, 1.0)
    sustained_tail_score = _clamp(sustained_tail_count / max(sustained_required_hits, 1), 0.0, 1.0)
    sustained_score = _clamp(0.6 * sustained_ratio_score + 0.4 * sustained_tail_score, 0.0, 1.0)
    absolute_load_score = 0.0
    if absolute_floor > 0:
        absolute_full_cfg = dynamic_cfg.get("absolute_load_full") or {}
        absolute_full = _safe_float(
            absolute_full_cfg.get(feature),
            absolute_floor + max(5.0, abs(absolute_floor) * 0.10),
        )
        absolute_load_score = _clamp(
            (current - absolute_floor) / max(absolute_full - absolute_floor, 1e-6),
            0.0,
            1.0,
        )
        sustained_score = max(sustained_score, 0.5 * sustained_tail_score + 0.5 * absolute_load_score)

    # Detector 3: trend detector.
    trend_window_points = max(6, int(dynamic_cfg.get("trend_window_points", min(len(recent), 15))))
    trend_window = recent[-min(recent.size, trend_window_points):]
    trend_slope = 0.0
    trend_r2 = 0.0
    if trend_window.size >= 3:
        x = np.arange(trend_window.size, dtype=np.float64)
        y = trend_window.astype(np.float64)
        slope, intercept = np.polyfit(x, y, 1)
        trend_slope = float(slope)
        pred = slope * x + intercept
        ss_res = float(np.sum((y - pred) ** 2))
        ss_tot = float(np.sum((y - np.mean(y)) ** 2))
        trend_r2 = 0.0 if ss_tot <= 1e-9 else max(0.0, 1.0 - ss_res / ss_tot)
    trend_slope_sigma = trend_slope / max(effective_std, 1e-6)
    trend_sigma_adj = _safe_float(feedback_profile.get("dynamic_trend_sigma_adjustment"), 0.0)
    trend_sigma_min = max(0.0, _safe_float(dynamic_cfg.get("trend_sigma_min", 0.05), 0.05) + trend_sigma_adj)
    trend_sigma_full = max(trend_sigma_min + 0.05, _safe_float(dynamic_cfg.get("trend_sigma_full", 0.35), 0.35) + trend_sigma_adj)
    trend_slope_score = _clamp(
        (trend_slope_sigma - trend_sigma_min) / max(trend_sigma_full - trend_sigma_min, 1e-6),
        0.0,
        1.0,
    )
    trend_r2_min = _clamp(_safe_float(dynamic_cfg.get("trend_r2_min", 0.2), 0.2), 0.0, 0.95)
    trend_r2_score = _clamp((trend_r2 - trend_r2_min) / max(1.0 - trend_r2_min, 1e-6), 0.0, 1.0)
    trend_score = _clamp(trend_slope_score * trend_r2_score, 0.0, 1.0)
    trend_breach_score = _clamp(_safe_float(dynamic_cfg.get("trend_breach_score", 0.55), 0.55), 0.0, 1.0)
    trend_breached = trend_score >= trend_breach_score

    # Multi-detector weighted fusion.
    detector_weights = _normalize_detector_weights(
        {
            "spike": _safe_float((dynamic_cfg.get("detector_weights") or {}).get("spike"), 0.5)
            + _safe_float(feedback_profile.get("dynamic_weight_spike_adjustment"), 0.0),
            "sustained": _safe_float((dynamic_cfg.get("detector_weights") or {}).get("sustained"), 0.3)
            + _safe_float(feedback_profile.get("dynamic_weight_sustained_adjustment"), 0.0),
            "trend": _safe_float((dynamic_cfg.get("detector_weights") or {}).get("trend"), 0.2)
            + _safe_float(feedback_profile.get("dynamic_weight_trend_adjustment"), 0.0),
        }
    )
    final_score = (
        detector_weights["spike"] * spike_score
        + detector_weights["sustained"] * sustained_score
        + detector_weights["trend"] * trend_score
    )

    warning_score_threshold = _clamp(_safe_float(dynamic_cfg.get("final_score_warning", 0.45), 0.45), 0.05, 0.9)
    alert_score_threshold = _clamp(
        max(warning_score_threshold + 0.05, _safe_float(dynamic_cfg.get("final_score_alert", 0.68), 0.68)),
        warning_score_threshold + 0.05,
        0.97,
    )
    critical_score_threshold = _clamp(
        max(alert_score_threshold + 0.05, _safe_float(dynamic_cfg.get("final_score_critical", 0.85), 0.85)),
        alert_score_threshold + 0.05,
        1.0,
    )

    anomaly_level = _score_to_state(final_score, warning_score_threshold, alert_score_threshold, critical_score_threshold)
    breached = state_rank(anomaly_level) >= state_rank("WARNING")
    effective_threshold = None
    if anomaly_level == "WARNING":
        effective_threshold = warning_score_threshold
    elif anomaly_level == "ALERT":
        effective_threshold = alert_score_threshold
    elif anomaly_level == "CRITICAL":
        effective_threshold = critical_score_threshold

    message = None
    if breached:
        message = (
            f"{_feature_label(feature)}触发动态融合告警：综合分={final_score:.3f}，"
            f"突发分={spike_score:.3f}，持续分={sustained_score:.3f}，趋势分={trend_score:.3f}，"
            f"权重(突发/持续/趋势)={detector_weights['spike']:.2f}/{detector_weights['sustained']:.2f}/{detector_weights['trend']:.2f}。"
        )

    return {
        "breached": breached,
        "trend_breached": trend_breached,
        "mean": mean_value,
        "std": effective_std,
        "raw_std": std_value,
        "std_floor_abs": std_floor_abs,
        "std_floor_ratio": std_floor_ratio,
        "trend": trend_value,
        "clamped_trend": clamped_trend,
        "adjusted_value": adjusted_value,
        "warning_threshold": warning_threshold,
        "alert_threshold": alert_threshold,
        "high_threshold": critical_score_threshold,
        "sustained_window_points": sustained_window_points,
        "warning_sustained_hits": sustained_required_hits,
        "alert_sustained_hits": min(sustained_window_points, sustained_required_hits + 1),
        "warning_over_count": sustained_over_count,
        "alert_over_count": sustained_over_count,
        "warning_sustained": sustained_over_count >= sustained_required_hits,
        "alert_sustained": sustained_tail_count >= sustained_required_hits,
        "sustained_baseline": sustained_baseline,
        "absolute_floor": None if absolute_floor <= 0 else absolute_floor,
        "absolute_load_score": absolute_load_score,
        "sustained_over_count": sustained_over_count,
        "sustained_tail_count": sustained_tail_count,
        "threshold": effective_threshold,
        "state": anomaly_level,
        "anomaly_level": anomaly_level,
        "warning_k": warning_k,
        "alert_k": alert_k,
        "beta": beta,
        "ema_alpha": alpha,
        "z_score": z_result.score,
        "detector_scores": {
            "spike": spike_score,
            "sustained": sustained_score,
            "trend": trend_score,
        },
        "detector_weights": detector_weights,
        "final_score": final_score,
        "warning_score_threshold": warning_score_threshold,
        "alert_score_threshold": alert_score_threshold,
        "critical_score_threshold": critical_score_threshold,
        "trend_slope": trend_slope,
        "trend_slope_sigma": trend_slope_sigma,
        "trend_r2": trend_r2,
        "message": message,
    }


def _evaluate_prediction(
    values: np.ndarray,
    current: float,
    predicted: Optional[float],
    prediction_cfg: Dict[str, Any],
) -> Dict[str, Any]:
    if predicted is None or not prediction_cfg.get("enabled", True):
        return {
            "breached": False,
            "predicted": predicted,
            "error": 0.0,
            "threshold": None,
            "message": None,
        }

    error = abs(predicted - current)
    diffs = np.abs(np.diff(values))
    baseline = float(np.mean(diffs)) if diffs.size else 0.0
    std = float(np.std(diffs)) if diffs.size else 0.0
    minimum = _safe_float(prediction_cfg.get("error_threshold"), 10.0)
    multiplier = _safe_float(prediction_cfg.get("multiplier"), 2.5)
    threshold = max(minimum, baseline + multiplier * std)
    breached = error > threshold
    message = f"预测残差异常：误差={error:.3f}，阈值={threshold:.3f}。" if breached else None
    return {
        "breached": breached,
        "predicted": predicted,
        "error": error,
        "threshold": threshold,
        "message": message,
    }


def _combine_feature_state(
    fixed_signal: Dict[str, Any],
    dynamic_signal: Dict[str, Any],
    prediction_signal: Dict[str, Any],
) -> str:
    if fixed_signal.get("breached"):
        return str(fixed_signal.get("state") or "NORMAL")
    if dynamic_signal.get("breached"):
        return str(dynamic_signal.get("state") or "WARNING")
    if prediction_signal.get("breached"):
        return "WARNING"
    return "NORMAL"


def detect_anomaly(
    model: Any,
    series: np.ndarray,
    window: int,
    feature_names: List[str],
    config: Dict[str, Any],
    feedback_profiles: Optional[Dict[str, Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    if series.shape[0] <= 5:
        return {
            "anomaly": False,
            "candidate_state": "NORMAL",
            "reason": "最近采样点不足，暂不执行异常检测。",
            "feature_signals": {},
            "triggered_features": [],
            "fixed_triggered_features": [],
        }

    feedback_profiles = feedback_profiles or {}
    fixed_cfg = config.get("fixed_thresholds", {})
    static_policy_cfg = config.get("static_severity_policy", {})
    dynamic_cfg = config.get("dynamic_threshold", {})
    prediction_cfg = config.get("prediction", {})
    decision_cfg = config.get("decision_engine", {})
    z_cfg = config.get("zscore", {})

    predicted = None
    if model is not None and prediction_cfg.get("enabled", True):
        X = np.expand_dims(series[-window:], axis=0)
        predicted = model.predict(X, verbose=0)[0]

    real = series[-1]
    feature_signals: Dict[str, Dict[str, Any]] = {}
    triggered_features: List[str] = []
    fixed_triggered_features: List[str] = []
    global_reasons: List[str] = []
    total_score = 0.0

    for idx, feature in enumerate(feature_names):
        current = float(real[idx])
        values = series[:, idx].astype(np.float32)
        recent = _choose_window(values, dynamic_cfg)
        feedback_profile = feedback_profiles.get(feature) or {}

        fixed_signal = _evaluate_fixed_threshold(feature, current, fixed_cfg, feedback_profile, static_policy_cfg)
        dynamic_signal = _evaluate_dynamic_threshold(feature, recent, values, current, dynamic_cfg, z_cfg, feedback_profile)
        prediction_signal = _evaluate_prediction(
            values,
            current,
            None if predicted is None else float(predicted[idx]),
            prediction_cfg,
        )

        mean_value = _safe_float(dynamic_signal.get("mean"))
        std_value = _safe_float(dynamic_signal.get("std"))
        trend_value = _safe_float(dynamic_signal.get("trend"))
        threshold_type = None
        threshold_value = None
        reasons: List[str] = []
        rule_name = None

        if fixed_signal.get("breached"):
            fixed_triggered_features.append(feature)
            threshold_type = "static"
            threshold_value = fixed_signal.get("threshold")
            rule_name = fixed_signal.get("rule_name")
            if fixed_signal.get("message"):
                reasons.append(str(fixed_signal.get("message")))
        elif dynamic_signal.get("breached"):
            threshold_type = "dynamic"
            threshold_value = dynamic_signal.get("threshold")
            if dynamic_signal.get("message"):
                reasons.append(str(dynamic_signal.get("message")))
            if prediction_signal.get("breached") and prediction_signal.get("message"):
                reasons.append(str(prediction_signal.get("message")))
        elif prediction_signal.get("breached"):
            reasons.append(str(prediction_signal.get("message")))

        feature_state = _combine_feature_state(fixed_signal, dynamic_signal, prediction_signal)
        score = 0.0
        if fixed_signal.get("breached"):
            score += 1.9 if feature_state == "CRITICAL" else 1.5
        elif dynamic_signal.get("breached"):
            dynamic_final_score = _clamp(_safe_float(dynamic_signal.get("final_score"), 0.0), 0.0, 1.0)
            score += 1.0 + 2.0 * dynamic_final_score
            if prediction_signal.get("breached"):
                score += 0.15
        elif prediction_signal.get("breached"):
            score += 0.45

        if state_rank(feature_state) >= state_rank("WARNING"):
            triggered_features.append(feature)
            total_score += score
            global_reasons.extend(f"{_feature_label(feature)}: {reason}" for reason in reasons if reason)

        feature_signals[feature] = {
            "feature": feature,
            "current": current,
            "mean": mean_value,
            "std": std_value,
            "trend": trend_value,
            "adjusted_value": dynamic_signal.get("adjusted_value"),
            "level": state_to_level(feature_state),
            "state": feature_state,
            "score": score,
            "threshold_type": threshold_type,
            "threshold_value": threshold_value,
            "high_threshold": dynamic_signal.get("high_threshold"),
            "static_threshold": fixed_signal,
            "dynamic_threshold": dynamic_signal,
            "prediction": prediction_signal,
            "breached": state_rank(feature_state) >= state_rank("WARNING"),
            "reasons": reasons,
            "recommendation": _build_recommendation(feature, threshold_type, None if rule_name is None else str(rule_name)),
        }

    top_signal = None
    if feature_signals:
        top_signal = max(
            feature_signals.values(),
            key=lambda item: (state_rank(str(item.get("state") or "NORMAL")), float(item.get("score") or 0.0)),
        )

    candidate_state = "NORMAL" if top_signal is None else str(top_signal.get("state") or "NORMAL")
    confidence = min(
        1.0,
        total_score / max(len(feature_names) * _safe_float(decision_cfg.get("confidence_divisor"), 2.5), 1.0),
    )
    anomaly_by_fixed_threshold = bool(fixed_triggered_features)
    anomaly_by_dynamic_threshold = any(
        bool((signal.get("dynamic_threshold") or {}).get("breached")) for signal in feature_signals.values()
    )
    anomaly = state_rank(candidate_state) >= state_rank("WARNING")

    top_dynamic = None if top_signal is None else top_signal.get("dynamic_threshold") or {}
    return {
        "anomaly": anomaly,
        "candidate_state": candidate_state,
        "anomaly_level": candidate_state,
        "anomaly_by_fixed_threshold": anomaly_by_fixed_threshold,
        "anomaly_by_dynamic_threshold": anomaly_by_dynamic_threshold,
        "anomaly_by_confidence": anomaly,
        "confidence": confidence,
        "feature_signals": feature_signals,
        "triggered_features": triggered_features,
        "fixed_triggered_features": fixed_triggered_features,
        "top_feature": None if top_signal is None else top_signal.get("feature"),
        "top_level": None if top_signal is None else top_signal.get("level"),
        "top_value": None if top_signal is None else top_signal.get("current"),
        "top_mean": None if top_signal is None else top_signal.get("mean"),
        "top_std": None if top_signal is None else top_signal.get("std"),
        "top_trend": None if top_signal is None else top_signal.get("trend"),
        "top_adjusted_value": None if top_signal is None else top_signal.get("adjusted_value"),
        "top_dynamic_score": None if top_signal is None else (top_signal.get("dynamic_threshold") or {}).get("final_score"),
        "top_detector_scores": None if top_signal is None else (top_signal.get("dynamic_threshold") or {}).get("detector_scores"),
        "top_detector_weights": None if top_signal is None else (top_signal.get("dynamic_threshold") or {}).get("detector_weights"),
        "top_high_threshold": None if top_signal is None else top_signal.get("high_threshold") or top_dynamic.get("high_threshold"),
        "top_threshold_type": None if top_signal is None else top_signal.get("threshold_type"),
        "top_threshold_value": None if top_signal is None else top_signal.get("threshold_value"),
        "reason": "；".join(global_reasons) if global_reasons else "当前未检测到异常。",
        "recommendation": None if top_signal is None else top_signal.get("recommendation"),
        "pred": None if predicted is None else predicted.tolist(),
        "real": real.tolist(),
        "feedback_profiles": feedback_profiles,
    }
