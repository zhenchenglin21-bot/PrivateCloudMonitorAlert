from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Dict


STATE_ORDER = {
    "NORMAL": 0,
    "WARNING": 1,
    "ALERT": 2,
    "CRITICAL": 3,
}

STATE_TO_LEVEL = {
    "NORMAL": "warning",
    "WARNING": "warning",
    "ALERT": "alert",
    "CRITICAL": "critical",
}


@dataclass
class StateTransition:
    from_state: str
    to_state: str
    changed: bool
    status: str
    reason: str
    abnormal_streak: int
    normal_streak: int


def state_rank(state: str) -> int:
    return STATE_ORDER.get(str(state or "NORMAL").upper(), 0)


def state_to_level(state: str) -> str:
    return STATE_TO_LEVEL.get(str(state or "NORMAL").upper(), "warning")


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _normalize_dynamic_anomaly_level(detection: Dict[str, Any]) -> str:
    primary = str(detection.get("anomaly_level") or "NORMAL").upper()
    candidate = str(detection.get("candidate_state") or "NORMAL").upper()
    if primary not in STATE_ORDER:
        primary = "NORMAL"
    if candidate not in STATE_ORDER:
        candidate = "NORMAL"
    level = primary if state_rank(primary) >= state_rank(candidate) else candidate
    return level


def _resolve_static_candidate_state(detection: Dict[str, Any]) -> str:
    feature_signals = detection.get("feature_signals") or {}
    fixed_features = detection.get("fixed_triggered_features") or []
    best_state = "NORMAL"

    for feature in fixed_features:
        signal = feature_signals.get(feature) or {}
        static_signal = signal.get("static_threshold") or {}
        if not bool(static_signal.get("breached")):
            continue
        candidate = str(static_signal.get("state") or signal.get("state") or "WARNING").upper()
        if candidate not in STATE_ORDER:
            candidate = "WARNING"
        if state_rank(candidate) > state_rank(best_state):
            best_state = candidate
    return best_state


def _resolve_high_threshold(detection: Dict[str, Any], machine_cfg: Dict[str, Any]) -> float:
    high_threshold = detection.get("top_high_threshold")
    if high_threshold is not None:
        return _safe_float(high_threshold, 0.0)
    base_threshold = detection.get("top_threshold_value")
    if base_threshold is None:
        return 0.0
    margin = max(0.0, _safe_float(machine_cfg.get("critical_margin_pct", 0.3), 0.3))
    return _safe_float(base_threshold, 0.0) * (1.0 + margin)


def _recovery_step(previous_state: str) -> str:
    if previous_state == "CRITICAL":
        return "ALERT"
    if previous_state == "ALERT":
        return "WARNING"
    if previous_state == "WARNING":
        return "NORMAL"
    return "NORMAL"


def _switch_source_if_needed(state: Any, source: str | None) -> None:
    previous_source = str(getattr(state, "last_abnormal_source", "") or "")
    current_source = str(source or "")
    if previous_source != current_source:
        state.abnormal_streak = 0
    state.last_abnormal_source = source


def apply_state_machine(state: Any, detection: Dict[str, Any], config: Dict[str, Any], now: datetime) -> StateTransition:
    machine_cfg = config.get("state_machine", {})
    recover_normals = max(1, int(machine_cfg.get("recover_normals", 3)))

    previous_state = str(getattr(state, "current_state", "NORMAL") or "NORMAL").upper()
    static_candidate = _resolve_static_candidate_state(detection)
    static_active = state_rank(static_candidate) >= state_rank("WARNING")
    dynamic_level = _normalize_dynamic_anomaly_level(detection)
    dynamic_active = state_rank(dynamic_level) >= state_rank("WARNING")

    # Static branch: always follow current highest static level.
    if static_active:
        _switch_source_if_needed(state, "static")
        state.normal_streak = 0
        state.abnormal_streak += 1
        state.dynamic_target_state = "NORMAL"
        state.dynamic_target_streak = 0
        if getattr(state, "active_since", None) is None:
            state.active_since = now

        target_state = static_candidate
        relation = "follow_current_static_highest"

        if target_state != previous_state:
            state.previous_state = previous_state
            state.current_state = target_state
            return StateTransition(
                from_state=previous_state,
                to_state=target_state,
                changed=True,
                status="firing",
                reason=(
                    f"static candidate={static_candidate}, relation={relation}, "
                    f"abnormal_streak={state.abnormal_streak}"
                ),
                abnormal_streak=state.abnormal_streak,
                normal_streak=state.normal_streak,
            )

        state.previous_state = previous_state
        return StateTransition(
            from_state=previous_state,
            to_state=previous_state,
            changed=False,
            status="steady",
            reason=(
                f"static candidate={static_candidate}, relation={relation}, "
                f"holding state={previous_state}, abnormal_streak={state.abnormal_streak}"
            ),
            abnormal_streak=state.abnormal_streak,
            normal_streak=state.normal_streak,
        )

    # Dynamic branch.
    if dynamic_active:
        _switch_source_if_needed(state, "dynamic")
        state.normal_streak = 0
        state.abnormal_streak += 1
        if getattr(state, "active_since", None) is None:
            state.active_since = now

        last_dynamic_target = str(getattr(state, "dynamic_target_state", "NORMAL") or "NORMAL").upper()
        if last_dynamic_target not in STATE_ORDER:
            last_dynamic_target = "NORMAL"
        dynamic_target_streak = int(getattr(state, "dynamic_target_streak", 0) or 0)
        if dynamic_level == last_dynamic_target:
            dynamic_target_streak += 1
        else:
            dynamic_target_streak = 1
        state.dynamic_target_state = dynamic_level
        state.dynamic_target_streak = dynamic_target_streak

        upgrade_hits = {
            "WARNING": max(1, int(machine_cfg.get("dynamic_warning_hits", 1))),
            "ALERT": max(1, int(machine_cfg.get("dynamic_alert_hits", 2))),
            "CRITICAL": max(1, int(machine_cfg.get("dynamic_critical_hits", 3))),
        }
        downgrade_hits = {
            "ALERT": max(1, int(machine_cfg.get("dynamic_recover_alert_hits", 2))),
            "WARNING": max(1, int(machine_cfg.get("dynamic_recover_warning_hits", 2))),
            "NORMAL": max(1, int(machine_cfg.get("dynamic_recover_normal_hits", recover_normals))),
        }

        target_state = previous_state
        required_hits = 0
        relation = "target_equals_current"
        if state_rank(dynamic_level) > state_rank(previous_state):
            required_hits = upgrade_hits.get(dynamic_level, 1)
            if dynamic_target_streak >= required_hits:
                target_state = dynamic_level
                relation = "upgrade_after_min_duration"
            else:
                relation = "upgrade_waiting_min_duration"
        elif state_rank(dynamic_level) < state_rank(previous_state):
            required_hits = downgrade_hits.get(dynamic_level, recover_normals)
            if dynamic_target_streak >= required_hits:
                target_state = dynamic_level
                relation = "downgrade_after_min_duration"
            else:
                relation = "downgrade_waiting_min_duration"

        if target_state != previous_state:
            state.previous_state = previous_state
            state.current_state = target_state
            return StateTransition(
                from_state=previous_state,
                to_state=target_state,
                changed=True,
                status="firing",
                reason=(
                    f"dynamic target={dynamic_level}, relation={relation}, "
                    f"target_streak={dynamic_target_streak}, required_hits={required_hits}, "
                    f"final_score={_safe_float(detection.get('top_dynamic_score'), 0.0):.4f}"
                ),
                abnormal_streak=state.abnormal_streak,
                normal_streak=state.normal_streak,
            )

        state.previous_state = previous_state
        return StateTransition(
            from_state=previous_state,
            to_state=previous_state,
            changed=False,
            status="steady",
            reason=(
                f"dynamic target={dynamic_level}, relation={relation}, state remains {previous_state}, "
                f"target_streak={dynamic_target_streak}, required_hits={required_hits}"
            ),
            abnormal_streak=state.abnormal_streak,
            normal_streak=state.normal_streak,
        )

    # No abnormal signal: require stable recovery window, then stepwise downgrade.
    _switch_source_if_needed(state, None)
    state.abnormal_streak = 0
    state.normal_streak += 1
    state.dynamic_target_state = "NORMAL"
    state.dynamic_target_streak = 0

    target_state = previous_state
    if previous_state != "NORMAL" and state.normal_streak >= recover_normals:
        target_state = _recovery_step(previous_state)

    if target_state != previous_state:
        state.previous_state = previous_state
        state.current_state = target_state
        if target_state == "NORMAL":
            state.active_since = None
        return StateTransition(
            from_state=previous_state,
            to_state=target_state,
            changed=True,
            status="resolved" if target_state == "NORMAL" else "firing",
            reason=(
                f"stable recovery reached {state.normal_streak} cycles, "
                f"downgrade {previous_state} -> {target_state}"
            ),
            abnormal_streak=state.abnormal_streak,
            normal_streak=state.normal_streak,
        )

    state.previous_state = previous_state
    return StateTransition(
        from_state=previous_state,
        to_state=previous_state,
        changed=False,
        status="steady",
        reason="waiting for stable recovery window",
        abnormal_streak=state.abnormal_streak,
        normal_streak=state.normal_streak,
    )
