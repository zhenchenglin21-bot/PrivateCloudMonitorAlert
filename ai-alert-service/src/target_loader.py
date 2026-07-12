from __future__ import annotations

from typing import Any, Dict, List

import requests


def fetch_runtime_targets(config: Dict[str, Any]) -> List[Dict[str, str]]:
    backend_cfg = config.get("backend", {})
    base_url = backend_cfg.get("url")
    api_key = backend_cfg.get("api_key")
    path = backend_cfg.get("targets_path")
    if not base_url or not api_key or not path:
        return []

    try:
        response = requests.get(
            base_url.rstrip("/") + path,
            headers={"X-API-Key": api_key},
            timeout=5,
        )
        response.raise_for_status()
        payload = response.json().get("data") or []
    except Exception:
        return []

    targets: List[Dict[str, str]] = []
    for item in payload:
        if not isinstance(item, dict):
            continue
        host = str(item.get("host") or "").strip()
        if not host:
            continue
        role = str(item.get("role") or "host").strip().lower() or "host"
        query_host = str(item.get("queryHost") or host).strip() or host
        parent_host = str(item.get("parentHost") or "").strip()
        targets.append(
            {
                "host": host,
                "role": role,
                "query_host": query_host,
                "parent_host": parent_host,
            }
        )
    return targets
