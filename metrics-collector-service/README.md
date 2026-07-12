# Metrics Collector Service

This project receives monitoring data over HTTP and writes it into InfluxDB.

## Structure

```text
metrics-collector-service/
  configs/
    config.yml
  src/
    main.py
  requirements.txt
  README.md
```

## Install

```bash
pip install -r requirements.txt
```

## Run

```bash
python -m src.main --config configs/config.yml
```

The service starts on `0.0.0.0:9273` by default.

## API

- `GET /`
  - health check
- `POST /metrics`
  - accepts a single metric object, a metric list, or `{ "metrics": [...] }`

## Metric Format

```json
{
  "name": "cpu",
  "tags": {
    "host": "node-1"
  },
  "fields": {
    "usage": 75.2,
    "busy": true
  },
  "timestamp": 1713273600
}
```
