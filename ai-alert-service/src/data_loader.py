from __future__ import annotations

from typing import Dict, List
import warnings

import pandas as pd
from influxdb_client import InfluxDBClient
from influxdb_client.client.warnings import MissingPivotFunction

from .flux_target import build_role_filter, build_target_filter


PROCESS_NAME_COLUMNS = [
    "process_name",
    "pattern",
    "exe",
    "command",
    "proc",
    "name",
    "process",
    "pid",
]


def _run_query(client: InfluxDBClient, flux: str) -> pd.DataFrame:
    warnings.simplefilter("ignore", MissingPivotFunction)
    result = client.query_api().query_data_frame(flux)
    if isinstance(result, list):
        result = pd.concat(result, ignore_index=True) if result else pd.DataFrame()
    return result if isinstance(result, pd.DataFrame) else pd.DataFrame()


def query_metrics(client: InfluxDBClient, bucket: str, start: str, measurements: Dict[str, str | List[str]]) -> pd.DataFrame:
    frames = []
    for alias, flux in measurements.items():
        flux_candidates = flux if isinstance(flux, list) else [flux]
        result = pd.DataFrame()
        for candidate in flux_candidates:
            result = _run_query(client, candidate.replace("$bucket", bucket))
            if not result.empty:
                break
        if result.empty:
            continue
        result = result[["_time", "_value"]].rename(columns={"_value": alias})
        frames.append(result)
    if not frames:
        return pd.DataFrame()
    df = frames[0]
    for frame in frames[1:]:
        df = pd.merge(df, frame, on="_time", how="outer")
    df = df.sort_values("_time").reset_index(drop=True)
    return df


def _escape_flux_string(value: str) -> str:
    return str(value).replace("\\", "\\\\").replace('"', '\\"')


def query_process_candidates(
    client: InfluxDBClient,
    bucket: str,
    query_host: str,
    role: str,
    entity_name: str,
    history_minutes: int,
    fields: List[str],
    limit: int,
) -> List[Dict[str, object]]:
    candidates: List[Dict[str, object]] = []

    for field in fields:
        escaped_field = _escape_flux_string(field)
        flux = f"""
import "types"
from(bucket: "{bucket}")
  |> range(start: -{history_minutes}m)
  |> filter(fn: (r) => r._measurement == "procstat")
  |> filter(fn: (r) => r._field == "{escaped_field}")
  |> filter(fn: (r) => types.isType(v: r._value, type: "float") or types.isType(v: r._value, type: "int") or types.isType(v: r._value, type: "uint"))
{build_role_filter(role)}{build_target_filter(query_host, role, entity_name)}
"""
        frame = _run_query(client, flux)
        if frame.empty or "_value" not in frame.columns:
            continue

        available_name_columns = [column for column in PROCESS_NAME_COLUMNS if column in frame.columns]
        if not available_name_columns:
            continue

        process_name = frame[available_name_columns].bfill(axis=1).iloc[:, 0]
        frame = frame.assign(process_name=process_name, field=field)
        frame = frame.dropna(subset=["process_name", "_value"])
        if frame.empty:
            continue

        latest = (
            frame.sort_values("_time")
            .groupby("process_name", as_index=False)
            .tail(1)
            .sort_values("_value", ascending=False)
            .head(limit)
        )

        for _, row in latest.iterrows():
            candidates.append(
                {
                    "process_name": str(row["process_name"]),
                    "field": field,
                    "value": float(row["_value"]),
                }
            )

    # Keep top-N per field to avoid one metric family crowding out another.
    # Example: memory bytes are usually much larger than cpu percentages.
    candidates.sort(key=lambda item: (str(item.get("field") or ""), -float(item.get("value") or 0.0)))
    return candidates


def query_process_history(
    client: InfluxDBClient,
    bucket: str,
    query_host: str,
    role: str,
    entity_name: str,
    history_minutes: int,
    process_name: str,
    field: str,
) -> pd.DataFrame:
    escaped_process = _escape_flux_string(process_name)
    escaped_field = _escape_flux_string(field)
    process_match = " or ".join(
        [
            f'(exists r.{column} and string(v: r.{column}) == "{escaped_process}")'
            for column in PROCESS_NAME_COLUMNS
        ]
    )
    flux = f"""
from(bucket: "{bucket}")
  |> range(start: -{history_minutes}m)
  |> filter(fn: (r) => r._measurement == "procstat")
  |> filter(fn: (r) => r._field == "{escaped_field}")
{build_role_filter(role)}{build_target_filter(query_host, role, entity_name)}
  |> filter(fn: (r) => {process_match})
  |> aggregateWindow(every: 10s, fn: mean, createEmpty: false)
"""
    frame = _run_query(client, flux)
    if frame.empty:
        return pd.DataFrame()
    return frame[["_time", "_value"]].sort_values("_time").reset_index(drop=True)
