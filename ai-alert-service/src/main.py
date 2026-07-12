import argparse
import os
import sys

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(CURRENT_DIR)

try:
    from .scheduler import loop
except ImportError:
    if PROJECT_ROOT not in sys.path:
        sys.path.insert(0, PROJECT_ROOT)
    from src.scheduler import loop


def resolve_config_path(path: str) -> str:
    if os.path.isabs(path):
        return path
    project_relative = os.path.join(PROJECT_ROOT, path)
    if os.path.exists(project_relative):
        return project_relative
    return os.path.abspath(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', default='configs/config.yml')
    parser.add_argument('--host', default='')
    args = parser.parse_args()
    loop(args.host, resolve_config_path(args.config))


if __name__ == '__main__':
    main()
