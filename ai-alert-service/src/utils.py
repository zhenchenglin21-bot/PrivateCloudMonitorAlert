import math
from dataclasses import dataclass
from typing import Any, Dict
import yaml


def load_config(path: str) -> Dict[str, Any]:
    with open(path, 'r', encoding='utf-8') as f:
        return yaml.safe_load(f)


@dataclass
class ZScoreResult:
    score: float
    is_anomaly: bool


def zscore(value: float, mean: float, std: float, threshold: float) -> ZScoreResult:
    if std == 0 or math.isnan(std):
        return ZScoreResult(0.0, False)
    score = abs((value - mean) / std)
    return ZScoreResult(score, score >= threshold)
