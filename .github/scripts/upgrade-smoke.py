"""Install a pinned baseline (1.0.0 or earlier 1.1.0), record data through its UI, then update it.

Only run on an isolated CI emulator. Both APKs must have a common debug
certificate. Never clear app data between the two installations.
"""
import hashlib
import json
import os
import pathlib
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PACKAGE = "ru.epbloger.trachu"
BASELINE_VERSION = sys.argv[3] if len(sys.argv) == 4 else "1.0.0"
assert BASELINE_VERSION in ("1.0.0", "1.1.0")
OUT = pathlib.Path("qa/upgrade" if BASELINE_VERSION == "1.0.0" else "qa/upgrade-v11")
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, binary=False, check=True):
    p = subprocess.run(["adb", *args], capture_output=True, check=check, timeout=60)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def version(expected):
    package = adb("shell", "dumpsys", "package", PACKAGE)
    assert re.search(r"\bversionName=" + re.escape(expected) + r"\s*$", package, re.M), expected
    return package


def snapshot():
    adb("shell", "am", "force-stop", PACKAGE)
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        for name in ("trachu.db", "trachu.db-wal", "trachu.db-shm"):
            data = adb("exec-out", "run-as", PACKAGE, "cat", "databases/" + name,
                       binary=True, check=False)
            if data:
                (root / name).write_bytes(data)
        assert (root / "trachu.db").is_file()
        with sqlite3.connect(root / "trachu.db") as db:
            assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
            rows = db.execute("SELECT kind,id,payload FROM ledger_records ORDER BY kind,id").fetchall()
            return [(kind, key, payload if kind == "meta" else json.loads(payload))
                    for kind, key, payload in rows]


def no_network():
    for _ in range(30):
        state = adb("shell", "dumpsys", "connectivity")
        if re.search(r"Active default network:\s*(none|null)\s*$", state, re.I | re.M):
            return state
        time.sleep(1)
    raise AssertionError("Upgrade emulator still has default network")


def verify_overview():
    result = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    assert "Status: ok" in result, result
    for _ in range(30):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/trachu-upgrade.xml")
            raw = adb("exec-out", "cat", "/sdcard/trachu-upgrade.xml")
            (OUT / "screen.xml").write_text(raw, encoding="utf-8")
            labels = {n.get(attr) for n in ET.fromstring(raw).iter("node")
                      for attr in ("text", "content-desc")}
            assert "Начнём с вашего счёта" not in labels, "Upgrade unexpectedly requires onboarding"
            if "Обзор" in labels:
                (OUT / "updated-dashboard.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
                return
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise AssertionError("Updated app never reached overview")


try:
    # The existing smoke exercises the real legacy UI and creates an exact
    # 42.50 RUB expense. It clears only the freshly installed legacy package.
    adb("uninstall", PACKAGE, check=False)
    baseline_args = [sys.executable, ".github/scripts/offline-smoke.py", sys.argv[1]]
    if BASELINE_VERSION == "1.0.0":
        baseline_args.append("--legacy")
    else:
        baseline_args.append("--previous-v11")
    subprocess.run(baseline_args,
                   env={**os.environ, "TRACHU_OFFLINE_QA_DIR": str(OUT / "baseline")},
                   check=True, timeout=300)
    (OUT / "before-package.txt").write_text(version(BASELINE_VERSION), encoding="utf-8")
    before = snapshot()
    assert any(kind == "entry" and data["minor"] == 4250 for kind, _, data in before)
    adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    (OUT / "connectivity-before.txt").write_text(no_network(), encoding="utf-8")
    assert "Success" in adb("install", "-r", sys.argv[2])
    (OUT / "after-package.txt").write_text(version("1.1.0"), encoding="utf-8")
    verify_overview()
    (OUT / "connectivity-after.txt").write_text(no_network(), encoding="utf-8")
    after = snapshot()
    assert before == after, "Financial ledger changed during update"
    digest = hashlib.sha256(json.dumps(after, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    result = f"PASS: {BASELINE_VERSION} -> 1.1.0, no clear or onboarding, {len(after)} identical ledger records, 4250 minor units; SHA256 {digest}\n"
    (OUT / "result.txt").write_text(result, encoding="utf-8")
    print(result)
finally:
    adb("shell", "cmd", "connectivity", "airplane-mode", "disable", check=False)
    adb("shell", "svc", "wifi", "enable", check=False)
    adb("shell", "svc", "data", "enable", check=False)
