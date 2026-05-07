#!/usr/bin/env bash
# سكربت تثبيت مشروع Python VPN
# Install script: creates a virtual environment and installs dependencies.
#
# Usage:  bash install.sh

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "==> Project directory: $PROJECT_DIR"

# 1) Check Python 3.9+
if ! command -v python3 &>/dev/null; then
  echo "ERROR: python3 not found. Install Python 3.9+ first." >&2
  exit 1
fi

PY_VERSION=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
PY_MAJOR=$(python3 -c "import sys; print(sys.version_info.major)")
PY_MINOR=$(python3 -c "import sys; print(sys.version_info.minor)")

if [[ "$PY_MAJOR" -lt 3 || ( "$PY_MAJOR" -eq 3 && "$PY_MINOR" -lt 9 ) ]]; then
  echo "ERROR: Python 3.9+ required (found $PY_VERSION)" >&2
  exit 1
fi

echo "==> Python version: $PY_VERSION  ✓"

# 2) Create virtual environment
VENV_DIR="$PROJECT_DIR/.venv"
if [[ ! -d "$VENV_DIR" ]]; then
  echo "==> Creating virtual environment in .venv ..."
  python3 -m venv "$VENV_DIR"
else
  echo "==> Virtual environment already exists — skipping creation."
fi

# 3) Install dependencies
echo "==> Installing dependencies from requirements.txt ..."
"$VENV_DIR/bin/pip" install --upgrade pip --quiet
"$VENV_DIR/bin/pip" install -r "$PROJECT_DIR/requirements.txt"

# 4) Copy example configs if config/ files don't exist yet
if [[ ! -f "$PROJECT_DIR/config/server.yaml" ]]; then
  cp "$PROJECT_DIR/config/server.example.yaml" "$PROJECT_DIR/config/server.yaml"
  echo "==> Created config/server.yaml — edit the 'password' field before use."
fi

if [[ ! -f "$PROJECT_DIR/config/client.yaml" ]]; then
  cp "$PROJECT_DIR/config/client.example.yaml" "$PROJECT_DIR/config/client.yaml"
  echo "==> Created config/client.yaml — edit 'server_host' and 'password' before use."
fi

# 5) Make scripts executable
chmod +x "$PROJECT_DIR/scripts/"*.sh

echo
echo "======================================================"
echo "  Installation complete!"
echo "======================================================"
echo
echo "Next steps:"
echo "  1. Edit config/server.yaml  (set a strong password)"
echo "  2. Edit config/client.yaml  (set server_host + same password)"
echo "  3. On the server, run:   sudo bash scripts/setup-server.sh"
echo "  4. Start the server:     sudo .venv/bin/python3 server.py"
echo "  5. Start the client:     sudo .venv/bin/python3 client.py"
echo
echo "Local end-to-end test (no second machine needed):"
echo "  sudo bash scripts/local-test.sh"
echo
