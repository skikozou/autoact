"""probe / flick 共通ヘルパ.

autoact への TCP 接続 (send) + よく使う op ラッパ (tap/swipe/read_editor/
clear_editor/launch_editor) + 定数 (HOST/PORT/IME_PKG/EDITOR_*).
"""
import json
import socket
import time

HOST = "127.0.0.1"
PORT = 8765
TIMEOUT = 60

IME_PKG    = "com.google.android.inputmethod.latin"
EDITOR_PKG = "ru.androidtools.texteditor"
EDITOR_ID  = f"{EDITOR_PKG}:id/big_text_view"
DEL_KEY    = f"{IME_PKG}:id/key_pos_del"


def send(cmd, args=None, timeout=TIMEOUT):
    req = json.dumps({"cmd": cmd, "args": args or {}}, ensure_ascii=False) + "\n"
    s = socket.socket()
    s.settimeout(timeout)
    s.connect((HOST, PORT))
    s.sendall(req.encode("utf-8"))
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
    s.close()
    return json.loads(buf.decode("utf-8"))


def tap(x, y):
    # 1px swipe を tap の代わりに (point-stroke gesture は最低 330ms かかる)
    return send("swipe", {"x1": x, "y1": y, "x2": x, "y2": y + 1, "durMs": 30})


def swipe(x1, y1, x2, y2, dur_ms=60):
    return send("swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "durMs": dur_ms})


def read_editor():
    r = send("find", {"by": "id", "value": EDITOR_ID, "limit": 1})
    m = r.get("result", {}).get("matches", [])
    return (m[0].get("text") or "") if m else ""


def clear_editor():
    for _ in range(3):
        t = read_editor()
        if not t:
            return
        for _ in range(len(t) + 3):
            send("click", {"by": "id", "value": DEL_KEY})
        time.sleep(0.1)


def launch_editor():
    """editor 起動 + EditText focus。成功 True / 失敗 False."""
    send("launch", {"package": EDITOR_PKG})
    time.sleep(1.5)
    r = send("find", {"by": "id", "value": EDITOR_ID, "limit": 1})
    if not r.get("result", {}).get("matches", []):
        return False
    send("click", {"by": "id", "value": EDITOR_ID})
    time.sleep(0.8)
    return True
