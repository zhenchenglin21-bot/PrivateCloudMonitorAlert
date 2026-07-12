import argparse
import atexit
import logging
import os
import sys
import traceback
from typing import Any

import yaml
from flask import Flask, jsonify, request
from influxdb_client import InfluxDBClient, Point, WritePrecision
from influxdb_client.client.write_api import SYNCHRONOUS

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(CURRENT_DIR)


def resolve_config_path(path: str) -> str:
    if os.path.isabs(path):
        return path
    project_relative = os.path.join(PROJECT_ROOT, path)
    if os.path.exists(project_relative):
        return project_relative
    return os.path.abspath(path)


def load_config(path: str) -> dict[str, Any]:
    with open(path, "r", encoding="utf-8") as file:
        return yaml.safe_load(file) or {}


def normalize_metrics(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, dict) and "metrics" in payload:
        metrics = payload["metrics"]
    elif isinstance(payload, list):
        metrics = payload
    else:
        metrics = [payload]

    return [item for item in metrics if isinstance(item, dict)]


def build_point(item: dict[str, Any]) -> Point | None:
    measurement = item.get("name", "unknown")
    point = Point(measurement)
    has_field = False

    tags = item.get("tags", {})
    if isinstance(tags, dict):
        for key, value in tags.items():
            point = point.tag(str(key), str(value))

    fields = item.get("fields", {})
    if isinstance(fields, dict):
        for key, value in fields.items():
            try:
                if isinstance(value, bool):
                    point = point.field(key, value)
                    has_field = True
                elif isinstance(value, (int, float)):
                    point = point.field(key, float(value))
                    has_field = True
                else:
                    point = point.field(key, str(value))
                    has_field = True
            except Exception:
                logging.exception("Field write conversion failed: %s=%s", key, value)

    timestamp = item.get("timestamp")
    if timestamp:
        point = point.time(timestamp, WritePrecision.S)

    if not has_field:
        logging.warning("Metric skipped because it has no valid fields: %s", measurement)
        return None

    return point


def create_app(config: dict[str, Any]) -> Flask:
    app = Flask(__name__)

    influx_config = config.get("influxdb", {})
    client = InfluxDBClient(
        url=influx_config["url"],
        token=influx_config["token"],
        org=influx_config["org"],
    )
    write_api = client.write_api(write_options=SYNCHRONOUS)

    @atexit.register
    def close_influx_client() -> None:
        client.close()

    @app.route("/", methods=["GET"])
    def index():
        return "Monitoring Receiver Running", 200

    @app.route("/metrics", methods=["POST"])
    def receive_metrics():
        try:
            logging.info("=" * 60)
            logging.info("Source IP: %s", request.remote_addr)

            raw_data = request.get_data(as_text=True)
            logging.info("Payload size: %s", len(raw_data))

            data = request.get_json(silent=True)
            if data is None:
                data = yaml.safe_load(raw_data)

            metrics = normalize_metrics(data)
            logging.info("Metrics count: %s", len(metrics))

            points = [point for item in metrics if (point := build_point(item)) is not None]

            if points:
                write_api.write(
                    bucket=influx_config["bucket"],
                    org=influx_config["org"],
                    record=points,
                )

            logging.info("Write success: %s", len(points))
            return jsonify({"status": "ok", "points": len(points)}), 200
        except Exception:
            logging.error("Metrics ingest failed")
            traceback.print_exc()
            return jsonify({"status": "error"}), 500

    return app


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="configs/config.yml")
    parser.add_argument("--host", default="")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()

    config = load_config(resolve_config_path(args.config))
    server_config = config.get("server", {})

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    app = create_app(config)
    host = args.host or server_config.get("host", "0.0.0.0")
    port = args.port or int(server_config.get("port", 9273))
    app.run(host=host, port=port)


if __name__ == "__main__":
    if PROJECT_ROOT not in sys.path:
        sys.path.insert(0, PROJECT_ROOT)
    main()
