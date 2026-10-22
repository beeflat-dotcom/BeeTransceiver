# -*- coding: utf-8 -*-
"""Generate a short-lived pairing code using the *running* Bee PTT relay status.

The running server may get its secret from BEE_AUDIO_UPLOAD_TOKEN instead of a
local audio_upload_token.txt. This script therefore never reads or prints the
server secret, and does not require it to exist in a file.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import secrets
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def check_server(url: str = "http://127.0.0.1:8769/ptt/health") -> dict:
    request = Request(url, headers={"Cache-Control": "no-cache"})
    try:
        with urlopen(request, timeout=6) as response:
            data = json.load(response)
    except (HTTPError, URLError, TimeoutError, OSError, ValueError) as exc:
        raise RuntimeError(
            "Windows PTT relay is not reachable on port 8769. "
            "Check that the Local AI server is running, then run Bee_PTT_Restart.cmd. "
            f"Details: {type(exc).__name__}: {exc}"
        ) from exc
    if not isinstance(data, dict) or data.get("ok") is not True:
        raise RuntimeError("Windows PTT relay health endpoint did not return ok=true")
    if data.get("auth_configured") is not True:
        raise RuntimeError(
            "The running Windows server has no audio upload authentication token. "
            "Check BEE_AUDIO_UPLOAD_TOKEN or audio_upload_token.txt on the server."
        )
    return data


def generate_code(folder: Path, lifetime_seconds: int = 180) -> str:
    code = f"{secrets.randbelow(1000000):06d}"
    record = {
        "code_sha256": hashlib.sha256(code.encode("ascii")).hexdigest(),
        "expires_at": time.time() + lifetime_seconds,
    }
    path = folder / "ptt_pairing_code.json"
    tmp = folder / ("ptt_pairing_code." + secrets.token_hex(6) + ".tmp")
    try:
        tmp.write_text(json.dumps(record), encoding="utf-8")
        os.replace(tmp, path)
    finally:
        try:
            tmp.unlink(missing_ok=True)
        except OSError:
            pass
    return code


def main() -> int:
    folder = Path(__file__).resolve().parent
    try:
        health = check_server()
        code = generate_code(folder)
    except (RuntimeError, OSError) as exc:
        print("\nERROR: " + str(exc), file=sys.stderr)
        return 1
    print("\nBee PTT device pairing (valid 3 minutes):")
    print("             " + code)
    print("Enter the code in the Android NET app. One phone per code.")
    print("Connected devices now: " + str(health.get("connected_devices", "unknown")))
    print("Once paired, this phone reconnects automatically. No daily code needed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

