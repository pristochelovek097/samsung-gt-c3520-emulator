#!/usr/bin/env python3
"""J2ME Phone OS — HTTP-frontend and launcher for FreeJ2ME."""
import json
import os
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
WEB = ROOT / "web"
GAMES = ROOT / "games"
FREEJ2ME = ROOT / "freej2me"
EMULATOR_JAR = FREEJ2ME / "build" / "freej2me.jar"
OWN_JAR = ROOT / "own-emulator" / "build" / "ownj2me.jar"

def detect_firmware():
    candidates = []
    env = os.environ.get("J2ME_FIRMWARE")
    if env: candidates.append(Path(env))
    candidates += [ROOT / "firmware", Path.home() / "Downloads" / "C3520XELJ1"]
    for c in candidates:
        if c.is_dir() and any(c.rglob("*.image")):
            return c
    return None

FIRMWARE_DIR = detect_firmware()

RUNNING = {}
PROCS = {}
LOCK = threading.Lock()

GAME_INFO = {
    "Snake":      "Классический змей. Ешь, расти, не разбивайся. Рекорд хранится в игре.",
    "Bricks":     "Арканоид с кирпичами. Отбивай шар, лови бонусы и проходи волны.",
    "Starfighter":"Космический шутер: сбивай врагов, уворачивайся, держи волну.",
}


def game_list():
    out = []
    for jad in sorted(GAMES.glob("*.jad")):
        jar = jad.with_suffix(".jar")
        if not jar.exists():
            continue
        text = jad.read_text(encoding="utf-8", errors="replace")
        name = re.search(r"^MIDlet-Name:\s*(.+)$", text, re.M)
        cls = re.search(r"^MIDlet-1:\s*[^,]*,\s*[^,]*,\s*(.+)$", text, re.M)
        out.append({
            "id": jar.stem,
            "name": name.group(1).strip() if name else jar.stem,
            "class": cls.group(1).strip() if cls else "",
            "file": jar.name,
            "size": jar.stat().st_size,
            "desc": GAME_INFO.get(jar.stem, "J2ME MIDlet, загруженный в коллекцию."),
        })
    return out


def write_config(suitename, width, height, phone):
    cfg = FREEJ2ME / "config" / suitename.replace(":", "")
    cfg.mkdir(parents=True, exist_ok=True)
    (cfg / "game.conf").write_text(
        f"width:{width}\nheight:{height}\nsound:on\nphone:{phone}\nrotate:off\nfps:0\n",
        encoding="utf-8",
    )


def launch_game(data):
    game_id = re.sub(r"[^A-Za-z0-9_.-]", "", str(data.get("id", "")))
    jar = GAMES / f"{game_id}.jar"
    if not jar.exists():
        return {"ok": False, "error": "Игра не найдена"}

    width = max(120, min(640, int(data.get("width", 240))))
    height = max(120, min(960, int(data.get("height", 320))))
    scale = max(1, min(5, int(data.get("scale", 2))))
    phone = str(data.get("phone", "Standard"))
    if phone not in ("Standard", "Nokia", "Siemens", "Motorola"):
        phone = "Standard"
    engine = str(data.get("engine", "own"))

    jad_name = re.search(r"^MIDlet-Name:\s*(.+)$", jar.with_suffix(".jad").read_text(
        encoding="utf-8", errors="replace"), re.M)
    suitename = jad_name.group(1).strip() if jad_name else game_id
    write_config(suitename, width, height, phone)

    log = ROOT / f"{game_id}.log"
    if engine in ("own", "own-shell") and OWN_JAR.exists():
        if engine == "own-shell":
            cmd = ["java", "-jar", str(OWN_JAR), "--shell", str(width), str(height),
                   str(scale), phone, str(GAMES), jar.name]
            if FIRMWARE_DIR:
                cmd += ["--firmware", str(FIRMWARE_DIR)]
        else:
            cmd = ["java", "-jar", str(OWN_JAR), str(jar), str(width), str(height), str(scale), phone]
    else:
        cmd = ["java", "-jar", str(EMULATOR_JAR), jar.as_uri(), str(width), str(height), str(scale)]
    with open(log, "ab") as out:
        proc = subprocess.Popen(cmd, cwd=str(FREEJ2ME), stdout=out, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True)
    with LOCK:
        RUNNING[str(proc.pid)] = {"pid": proc.pid, "game": game_id, "started": time.time()}
        PROCS[str(proc.pid)] = proc
    if engine in ("own", "own-shell") and OWN_JAR.exists():
        used = engine
    else:
        used = "freej2me"
    return {"ok": True, "pid": proc.pid, "name": suitename, "phone": phone, "engine": used}


def stop_game(pid):
    with LOCK:
        entry = RUNNING.pop(str(pid), None)
        PROCS.pop(str(pid), None)
    if not entry:
        return {"ok": False, "error": "Процесс не найден"}
    try:
        os.kill(int(pid), 15)
    except (ProcessLookupError, ValueError):
        pass
    return {"ok": True}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/games":
            return self._send(200, {"games": game_list()})
        if path == "/api/status":
            with LOCK:
                for key, proc in list(PROCS.items()):
                    if proc.poll() is not None:
                        RUNNING.pop(key, None)
                        PROCS.pop(key, None)
                return self._send(200, {"running": list(RUNNING.values())})
        if path == "/api/emulator":
            return self._send(200, {"ok": EMULATOR_JAR.exists(), "own": OWN_JAR.exists(), "java": True})
        if path == "/api/firmware":
            return self._send(200, {"dir": str(FIRMWARE_DIR) if FIRMWARE_DIR else None,
                                    "images": len(list(FIRMWARE_DIR.rglob("*.image"))) if FIRMWARE_DIR else 0})
        if path.startswith("/api/"):
            return self._send(404, {"error": "not found"})

        rel = path.lstrip("/") or "index.html"
        target = (WEB / rel).resolve()
        if not str(target).startswith(str(WEB.resolve())):
            return self._send(403, "forbidden")
        if not target.is_file():
            return self._send(404, "not found")
        ct = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
              ".js": "text/javascript; charset=utf-8", ".png": "image/png",
              ".svg": "image/svg+xml"}.get(target.suffix, "application/octet-stream")
        return self._send(200, target.read_bytes(), ct)

    def do_POST(self):
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            data = json.loads(raw or b"{}")
        except Exception:
            data = {}
        if path == "/api/launch":
            return self._send(200, launch_game(data))
        if path == "/api/stop":
            return self._send(200, stop_game(data.get("pid")))
        if path == "/api/clearlog":
            for log in ROOT.glob("*.log"):
                try:
                    log.write_text("", encoding="utf-8")
                except OSError:
                    pass
            return self._send(200, {"ok": True})
        return self._send(404, {"error": "not found"})


if __name__ == "__main__":
    port = 8848
    print(f"J2ME Phone OS → http://127.0.0.1:{port}")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
