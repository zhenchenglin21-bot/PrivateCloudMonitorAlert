from __future__ import annotations

import json
import smtplib
from datetime import datetime
from email.message import EmailMessage
from typing import Any, Dict, Optional

import requests


def _fmt_number(value: Any) -> str:
    if isinstance(value, (int, float)):
        return f"{float(value):.3f}"
    return "--"


def _send_email(alert: Dict[str, Any], config: Dict[str, Any]) -> None:
    email_cfg = ((config.get("notification") or {}).get("email") or {})
    if not email_cfg.get("enabled"):
        return

    recipients = email_cfg.get("to") or []
    if not recipients:
        return

    msg = EmailMessage()
    msg["Subject"] = (
        f"[Intelligent Alert] {alert.get('host')} "
        f"{alert.get('previousState', 'NORMAL')} -> {alert.get('alertState', 'NORMAL')}"
    )
    msg["From"] = email_cfg.get("from") or email_cfg.get("username")
    msg["To"] = ", ".join(recipients)
    msg.set_content(
        "\n".join(
            [
                f"Host: {alert.get('host')}",
                f"Rule: {alert.get('ruleName')}",
                f"Metric: {alert.get('metricName') or '--'}",
                f"Status: {alert.get('status')}",
                f"State: {alert.get('previousState', 'NORMAL')} -> {alert.get('alertState', 'NORMAL')}",
                f"Level: {alert.get('level')}",
                f"Current Value: {_fmt_number(alert.get('value'))}",
                f"Threshold Type: {alert.get('thresholdType') or '--'}",
                f"Effective Threshold: {_fmt_number(alert.get('thresholdValue'))}",
                f"Static Threshold: {_fmt_number(alert.get('staticThreshold'))}",
                f"Dynamic Threshold: {_fmt_number(alert.get('dynamicThreshold'))}",
                f"Mean: {_fmt_number(alert.get('meanValue'))}",
                f"Std: {_fmt_number(alert.get('stdValue'))}",
                f"Trend(current-mean): {_fmt_number(alert.get('trendValue'))}",
                f"Reason: {alert.get('reason')}",
                f"Recommendation: {alert.get('recommendation') or '--'}",
                f"Confidence: {_fmt_number(alert.get('confidenceScore'))}",
                f"Time: {alert.get('time')}",
            ]
        )
    )

    host = email_cfg.get("smtp_host")
    port = int(email_cfg.get("smtp_port", 465))
    username = email_cfg.get("username")
    password = email_cfg.get("password")
    use_ssl = bool(email_cfg.get("use_ssl", True))

    if not host or not username or not password:
        return

    server = smtplib.SMTP_SSL(host, port) if use_ssl else smtplib.SMTP(host, port)
    try:
        if not use_ssl:
            server.starttls()
        server.login(username, password)
        server.send_message(msg)
    finally:
        server.quit()


def emit_alert(alert: Dict[str, Any], config: Dict[str, Any]):
    if config.get("output", {}).get("enable_stdout", True):
        print(json.dumps(alert, ensure_ascii=False))

    if config.get("output", {}).get("enable_file", False):
        path = config.get("output", {}).get("file_path", "alerts.log")
        with open(path, "a", encoding="utf-8") as file:
            file.write(json.dumps(alert, ensure_ascii=False) + "\n")

    _send_email(alert, config)

    backend = config.get("backend") or {}
    if backend.get("url") and backend.get("ingest_path") and backend.get("api_key"):
        try:
            response = requests.post(
                backend["url"].rstrip("/") + backend["ingest_path"],
                json=alert,
                headers={"X-API-Key": backend["api_key"]},
                timeout=5,
            )
            body: Dict[str, Any] | None = None
            try:
                body = response.json()
            except Exception:
                body = None

            if not response.ok:
                print(
                    f"[backend] ingest http_error status={response.status_code} "
                    f"body={response.text}"
                )
            elif isinstance(body, dict) and body.get("code") != 200:
                print(
                    f"[backend] ingest business_error code={body.get('code')} "
                    f"message={body.get('message')}"
                )
            else:
                print("[backend] ingest success")
        except Exception as exc:
            print(f"[backend] ingest exception: {exc}")


def build_alert(
    host: str,
    metric: str,
    value: float,
    reason: str,
    status: str,
    *,
    level: str = "warning",
    recommendation: Optional[str] = None,
    decision_source: str = "heuristic",
    duration_seconds: Optional[int] = None,
    confidence_score: Optional[float] = None,
    fingerprint: Optional[str] = None,
    context: Optional[Dict[str, Any]] = None,
    metric_name: Optional[str] = None,
    alert_state: Optional[str] = None,
    previous_state: Optional[str] = None,
    threshold_type: Optional[str] = None,
    threshold_value: Optional[float] = None,
    static_threshold: Optional[float] = None,
    dynamic_threshold: Optional[float] = None,
    mean_value: Optional[float] = None,
    std_value: Optional[float] = None,
    trend_value: Optional[float] = None,
) -> Dict[str, Any]:
    payload = {
        "host": host,
        "ruleName": metric,
        "metricName": metric_name,
        "alertState": alert_state,
        "previousState": previous_state,
        "thresholdType": threshold_type,
        "thresholdValue": threshold_value,
        "staticThreshold": static_threshold,
        "dynamicThreshold": dynamic_threshold,
        "meanValue": mean_value,
        "stdValue": std_value,
        "trendValue": trend_value,
        "level": level,
        "status": status,
        "value": value,
        "reason": reason,
        "recommendation": recommendation,
        "decisionSource": decision_source,
        "durationSeconds": duration_seconds,
        "confidenceScore": confidence_score,
        "fingerprint": fingerprint,
        "contextJson": json.dumps(context, ensure_ascii=False) if context else None,
        "time": datetime.utcnow().isoformat() + "Z",
    }
    return payload
