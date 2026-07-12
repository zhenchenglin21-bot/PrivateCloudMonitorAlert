from __future__ import annotations

from typing import Any, Dict, Iterable

import requests


def _safe_int(value: Any, default: int = 0) -> int:
    try:
        if value is None:
            return default
        return int(value)
    except (TypeError, ValueError):
        return default


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _clamp(value: float, lower: float, upper: float) -> float:
    return max(lower, min(upper, value))


def _build_default_profile() -> Dict[str, Any]:
    return {
        "total_feedback": 0,
        "true_count": 0,
        "false_count": 0,
        "false_positive_rate": 0.0,
        "effective_rate": 0.0,
        "fixed_threshold_offset_pct": 0.0,
        "dynamic_k_adjustment": 0.0,
        "dynamic_warning_k_adjustment": 0.0,
        "dynamic_alert_k_adjustment": 0.0,
        "trend_beta_adjustment": 0.0,
        "dynamic_weight_spike_adjustment": 0.0,
        "dynamic_weight_sustained_adjustment": 0.0,
        "dynamic_weight_trend_adjustment": 0.0,
        "dynamic_sustained_hits_adjustment": 0.0,
        "dynamic_trend_sigma_adjustment": 0.0,
        "confidence_adjustment": 0.0,
    }


def _compute_learning_adjustments(profile: Dict[str, Any], learning_cfg: Dict[str, Any]) -> Dict[str, Any]:
    total_feedback = int(profile.get("total_feedback") or 0)
    if total_feedback <= 0:
        return profile
    min_feedback_samples = max(1, int(learning_cfg.get("min_feedback_samples", 20)))
    if total_feedback < min_feedback_samples:
        return profile

    true_count = int(profile.get("true_count") or 0)
    false_count = int(profile.get("false_count") or 0)
    false_positive_rate = _safe_float(profile.get("false_positive_rate"), 0.0)
    effective_rate = _safe_float(profile.get("effective_rate"), 0.0)
    imbalance = (false_count - true_count) / max(total_feedback, 1)

    feedback_cycle_size = max(1, int(learning_cfg.get("feedback_cycle_size", 20)))
    adjustment_rounds = total_feedback // feedback_cycle_size
    if adjustment_rounds <= 0:
        return profile
    adjustment_rounds = min(adjustment_rounds, max(1, int(learning_cfg.get("max_adjustment_rounds", 6))))
    imbalance_threshold = _safe_float(learning_cfg.get("imbalance_threshold", 0.1), 0.1)

    # 1) 静态阈值偏移：误报多则放宽，正确率高则轻微收紧
    fixed_step = _safe_float(learning_cfg.get("fixed_offset_step", 0.03), 0.03)
    max_fixed_offset = _safe_float(learning_cfg.get("max_fixed_offset_pct", 0.2), 0.2)
    fixed_offset = 0.0
    fixed_scale = max(0.0, min(1.0, abs(imbalance)))
    if false_count > true_count and (imbalance >= imbalance_threshold or false_positive_rate >= 0.6):
        fixed_offset = fixed_step * adjustment_rounds * max(0.5, fixed_scale)
    elif true_count > false_count and (-imbalance >= imbalance_threshold or effective_rate >= 0.65):
        fixed_offset = -fixed_step * 0.5 * adjustment_rounds * max(0.5, fixed_scale)
    profile["fixed_threshold_offset_pct"] = _clamp(fixed_offset, -max_fixed_offset / 2.0, max_fixed_offset)

    # 2) 动态阈值 k 调整：误报多 => 提高 k；有效多 => 降低 k
    k_up_step = _safe_float(learning_cfg.get("dynamic_k_up_step", 0.08), 0.08)
    k_down_step = _safe_float(learning_cfg.get("dynamic_k_down_step", 0.04), 0.04)
    k_adjustment_limit = _safe_float(learning_cfg.get("dynamic_k_adjustment_limit", 1.0), 1.0)
    k_adj = 0.0
    k_scale = max(0.0, min(1.0, max(abs(imbalance), abs(false_positive_rate - effective_rate))))
    if false_count > true_count and (imbalance >= imbalance_threshold or false_positive_rate >= 0.6):
        k_adj = k_up_step * adjustment_rounds * max(0.5, k_scale)
    elif true_count > false_count and (-imbalance >= imbalance_threshold or effective_rate >= 0.65):
        k_adj = -k_down_step * adjustment_rounds * max(0.5, k_scale)
    k_adj = _clamp(k_adj, -k_adjustment_limit, k_adjustment_limit)
    profile["dynamic_k_adjustment"] = k_adj
    profile["dynamic_warning_k_adjustment"] = k_adj
    profile["dynamic_alert_k_adjustment"] = k_adj

    # 3) 趋势补偿 beta 调整（可选增强）
    beta_step = _safe_float(learning_cfg.get("trend_beta_step", 0.03), 0.03)
    beta_limit = _safe_float(learning_cfg.get("trend_beta_adjustment_limit", 0.2), 0.2)
    beta_false_rate = _safe_float(learning_cfg.get("trend_beta_false_rate", 0.75), 0.75)
    beta_effective_rate = _safe_float(learning_cfg.get("trend_beta_effective_rate", 0.75), 0.75)
    beta_adj = 0.0
    if false_positive_rate >= beta_false_rate:
        beta_adj = -beta_step * min(adjustment_rounds, 3)
    elif effective_rate >= beta_effective_rate:
        beta_adj = beta_step * min(adjustment_rounds, 3)
    profile["trend_beta_adjustment"] = _clamp(beta_adj, -beta_limit, beta_limit)

    # 4) 置信度门槛微调（保留原能力）
    confidence_step = _safe_float(learning_cfg.get("confidence_step", 0.05), 0.05)
    confidence_adj = 0.0
    if false_count > true_count:
        confidence_adj = confidence_step
    elif true_count > false_count:
        confidence_adj = -confidence_step / 2.0
    profile["confidence_adjustment"] = confidence_adj

    # 5) Multi-detector weight adaptation for dynamic fusion.
    weight_step = _safe_float(learning_cfg.get("detector_weight_step", 0.04), 0.04)
    weight_limit = _safe_float(learning_cfg.get("detector_weight_adjustment_limit", 0.25), 0.25)
    weight_scale = max(0.0, min(1.0, max(abs(imbalance), abs(false_positive_rate - effective_rate))))
    spike_adj = 0.0
    sustained_adj = 0.0
    trend_adj = 0.0
    if false_count > true_count and (imbalance >= imbalance_threshold or false_positive_rate >= 0.6):
        spike_adj = -weight_step * adjustment_rounds * max(0.5, weight_scale)
        sustained_adj = weight_step * adjustment_rounds * max(0.5, weight_scale)
        trend_adj = -0.5 * weight_step * adjustment_rounds * max(0.5, weight_scale)
    elif true_count > false_count and (-imbalance >= imbalance_threshold or effective_rate >= 0.65):
        spike_adj = 0.6 * weight_step * adjustment_rounds * max(0.5, weight_scale)
        sustained_adj = -0.8 * weight_step * adjustment_rounds * max(0.5, weight_scale)
        trend_adj = 0.6 * weight_step * adjustment_rounds * max(0.5, weight_scale)
    profile["dynamic_weight_spike_adjustment"] = _clamp(spike_adj, -weight_limit, weight_limit)
    profile["dynamic_weight_sustained_adjustment"] = _clamp(sustained_adj, -weight_limit, weight_limit)
    profile["dynamic_weight_trend_adjustment"] = _clamp(trend_adj, -weight_limit, weight_limit)

    # 6) Sustained duration adaptation.
    sustained_hits_step = _safe_float(learning_cfg.get("sustained_hits_step", 1.0), 1.0)
    sustained_hits_limit = _safe_float(learning_cfg.get("sustained_hits_adjustment_limit", 4.0), 4.0)
    sustained_hits_adj = 0.0
    if false_count > true_count:
        sustained_hits_adj = sustained_hits_step * adjustment_rounds
    elif true_count > false_count:
        sustained_hits_adj = -0.5 * sustained_hits_step * adjustment_rounds
    profile["dynamic_sustained_hits_adjustment"] = _clamp(
        sustained_hits_adj,
        -sustained_hits_limit,
        sustained_hits_limit,
    )

    # 7) Trend sensitivity adaptation.
    trend_sigma_step = _safe_float(learning_cfg.get("trend_sigma_step", 0.03), 0.03)
    trend_sigma_limit = _safe_float(learning_cfg.get("trend_sigma_adjustment_limit", 0.25), 0.25)
    trend_sigma_adj = 0.0
    if false_count > true_count:
        trend_sigma_adj = trend_sigma_step * adjustment_rounds
    elif true_count > false_count:
        trend_sigma_adj = -0.5 * trend_sigma_step * adjustment_rounds
    profile["dynamic_trend_sigma_adjustment"] = _clamp(
        trend_sigma_adj,
        -trend_sigma_limit,
        trend_sigma_limit,
    )

    return profile


def fetch_feedback_profiles(
    host: str,
    rule_name: str,
    metric_names: Iterable[str],
    config: Dict[str, Any],
) -> Dict[str, Dict[str, Any]]:
    learning_cfg = config.get("feedback_learning", {})
    backend_cfg = config.get("backend", {})
    result: Dict[str, Dict[str, Any]] = {}

    metrics = [metric for metric in metric_names if metric]
    if not metrics:
        return result

    if not learning_cfg.get("enabled", True):
        return {metric: _build_default_profile() for metric in metrics}

    base_url = backend_cfg.get("url")
    summary_path = learning_cfg.get("summary_path")
    api_key = backend_cfg.get("api_key")
    if not base_url or not summary_path or not api_key:
        return {metric: _build_default_profile() for metric in metrics}

    for metric_name in metrics:
        profile = _build_default_profile()
        try:
            response = requests.get(
                base_url.rstrip("/") + summary_path,
                params={"host": host, "ruleName": rule_name, "metricName": metric_name},
                headers={"X-API-Key": api_key},
                timeout=_safe_float(learning_cfg.get("timeout_seconds", 5), 5.0),
            )
            response.raise_for_status()
            body = response.json()
            if isinstance(body, dict) and "data" in body:
                code = body.get("code")
                if code not in (None, 200):
                    raise ValueError(f"feedback summary error: code={code}")
                payload = body.get("data") or {}
            else:
                payload = body if isinstance(body, dict) else {}

            effective_count = _safe_int(payload.get("trueCount"), _safe_int(payload.get("effectiveCount"), 0))
            false_count = _safe_int(payload.get("falseCount"), _safe_int(payload.get("falsePositiveCount"), 0))
            total_count = _safe_int(payload.get("totalCount"), 0)
            if total_count <= 0:
                total_count = max(0, effective_count + false_count)
            if effective_count + false_count > total_count:
                total_count = effective_count + false_count

            effective_rate = _safe_float(payload.get("effectiveRate"), 0.0)
            false_positive_rate = _safe_float(payload.get("falsePositiveRate"), 0.0)
            if total_count > 0:
                if effective_rate <= 0.0:
                    effective_rate = effective_count / total_count
                if false_positive_rate <= 0.0:
                    false_positive_rate = false_count / total_count

            profile["total_feedback"] = total_count
            profile["true_count"] = effective_count
            profile["false_count"] = false_count
            profile["effective_rate"] = effective_rate
            profile["false_positive_rate"] = false_positive_rate
        except Exception:
            result[metric_name] = profile
            continue

        result[metric_name] = _compute_learning_adjustments(profile, learning_cfg)

    return result
