# -*- coding: utf-8 -*-
"""WebSocket prerequisite check without PowerShell Python -c quoting issues."""
import importlib
import sys

needed = ("fastapi", "uvicorn", "websockets")
missing = []
for module in needed:
    try:
        importlib.import_module(module)
    except Exception as exc:
        print(f"[MISSING] {module}: {type(exc).__name__}: {exc}")
        missing.append(module)

if missing:
    print("WebSocket Python dependencies are not ready: " + ", ".join(missing))
    print('Install with: python -m pip install "uvicorn[standard]"')
    sys.exit(2)

from fastapi import WebSocket
print("Python WebSocket stack: OK")
