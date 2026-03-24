#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV_DIR="${ROOT_DIR}/.venv"

if ! command -v python3 >/dev/null 2>&1; then
  echo "[auto-ragas] python3 not found"
  exit 1
fi

if [[ ! -d "${VENV_DIR}" ]]; then
  python3 -m venv "${VENV_DIR}"
fi

source "${VENV_DIR}/bin/activate"
python -m pip install -q --upgrade pip
python -m pip install -q -r "${ROOT_DIR}/requirements.txt"

python "${ROOT_DIR}/run_auto_ragas.py" "$@"
