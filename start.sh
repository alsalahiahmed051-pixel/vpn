#!/usr/bin/env bash
# ===================================================================
# سكربت التشغيل الكامل — يشغّل السيرفر ويبني APK بأمر واحد
# One-command launcher: sets up server + shows APK install info
# Usage: sudo bash start.sh [server|build-apk|test|all]
# ===================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJECT/android/app-debug.apk"
SERVER_CFG="$PROJECT/config/server.yaml"
CLIENT_CFG="$PROJECT/config/client.yaml"

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

banner() {
    echo -e "${BLUE}"
    echo "  ██████╗ ██╗   ██╗████████╗██╗  ██╗ ██████╗ ███╗   ██╗"
    echo "  ██╔══██╗╚██╗ ██╔╝╚══██╔══╝██║  ██║██╔═══██╗████╗  ██║"
    echo "  ██████╔╝ ╚████╔╝    ██║   ███████║██║   ██║██╔██╗ ██║"
    echo "  ██╔═══╝   ╚██╔╝     ██║   ██╔══██║██║   ██║██║╚██╗██║"
    echo "  ██║        ██║      ██║   ██║  ██║╚██████╔╝██║ ╚████║"
    echo "  ╚═╝        ╚═╝      ╚═╝   ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═══╝"
    echo -e "${NC}"
    echo -e "  ${GREEN}Python VPN — AES-256-GCM over UDP${NC}"
    echo
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        echo -e "${RED}ERROR: يجب التشغيل كـ root (sudo bash start.sh)${NC}" >&2
        exit 1
    fi
}

ensure_venv() {
    if [ ! -d "$PROJECT/.venv" ]; then
        echo -e "${YELLOW}==> Installing Python dependencies...${NC}"
        bash "$PROJECT/install.sh"
    fi
}

cmd_server() {
    check_root
    ensure_venv

    echo -e "${GREEN}==> Starting VPN server...${NC}"
    echo -e "    Config: $SERVER_CFG"
    echo -e "    Password: $(grep 'password:' "$SERVER_CFG" | awk '{print $2}' | tr -d '\"')"
    echo
    echo -e "${YELLOW}    Server running. Press Ctrl+C to stop.${NC}"
    echo

    # Setup NAT + forwarding
    bash "$PROJECT/scripts/setup-server.sh" 2>/dev/null || true

    # Start server
    "$PROJECT/.venv/bin/python3" "$PROJECT/server.py" --config "$SERVER_CFG"
}

cmd_build_apk() {
    echo -e "${GREEN}==> Building Android APK...${NC}"
    bash "$PROJECT/android/build-apk.sh"

    PASSWORD=$(grep 'password:' "$SERVER_CFG" | awk '{print $2}' | tr -d '"')
    SERVER_HOST=$(hostname -I | awk '{print $1}')

    echo
    echo -e "${BLUE}════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  APK built!  →  android/app-debug.apk${NC}"
    echo
    echo -e "  📱 ${YELLOW}أدخل هذه البيانات في تطبيق Android:${NC}"
    echo -e "     Server Host : ${GREEN}$SERVER_HOST${NC}"
    echo -e "     Port        : ${GREEN}51820${NC}"
    echo -e "     Password    : ${GREEN}$PASSWORD${NC}"
    echo -e "     Tunnel IP   : ${GREEN}10.8.0.2${NC}"
    echo
    echo -e "  📦 ${YELLOW}تثبيت التطبيق:${NC}"
    echo -e "     ${GREEN}adb install android/app-debug.apk${NC}"
    echo -e "     أو انقل الملف لجهاز Android وافتحه"
    echo -e "${BLUE}════════════════════════════════════════════════════${NC}"
}

cmd_test() {
    check_root
    ensure_venv
    echo -e "${GREEN}==> Running local tunnel test...${NC}"
    bash "$PROJECT/scripts/local-test.sh"
}

cmd_all() {
    cmd_build_apk
    echo
    cmd_server
}

# -------------------------------------------------------------------
banner
MODE="${1:-all}"

case "$MODE" in
    server)    cmd_server ;;
    build-apk) cmd_build_apk ;;
    test)      cmd_test ;;
    all)       cmd_all ;;
    *)
        echo "Usage: sudo bash start.sh [server|build-apk|test|all]"
        echo
        echo "  server     — شغّل سيرفر VPN فقط"
        echo "  build-apk  — ابنِ APK فقط (بدون root)"
        echo "  test       — اختبار النفق محلياً"
        echo "  all        — ابنِ APK ثم شغّل السيرفر (افتراضي)"
        exit 1
        ;;
esac
