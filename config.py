"""
Tiny YAML config loader with sensible defaults.
"""

from pathlib import Path
import yaml


SERVER_DEFAULTS = {
    "listen": "0.0.0.0",
    "port": 51820,
    "tun_name": "vpn0",
    "tun_ip": "10.8.0.1/24",
    "mtu": 1400,
    "keepalive_seconds": 25,
    "log_level": "INFO",
}

CLIENT_DEFAULTS = {
    "server_host": "127.0.0.1",
    "server_port": 51820,
    "tun_name": "vpn0",
    "tun_ip": "10.8.0.2/24",
    "mtu": 1400,
    "keepalive_seconds": 25,
    "route_all_traffic": False,  # set True to send default route through VPN
    "log_level": "INFO",
}


def load(path: str, defaults: dict) -> dict:
    p = Path(path)
    if not p.exists():
        raise FileNotFoundError(f"Config file not found: {path}")
    with p.open("r", encoding="utf-8") as f:
        user_cfg = yaml.safe_load(f) or {}
    cfg = {**defaults, **user_cfg}
    if "password" not in cfg or not cfg["password"]:
        raise ValueError(
            "Config must define a 'password' (shared between client and server)"
        )
    return cfg
