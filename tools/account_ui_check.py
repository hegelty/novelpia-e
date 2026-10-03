#!/usr/bin/env python3
"""Run/read only the native account-check UI on a local emulator.

Does not restart, install, log in, or attach a debugger. UI XML is handled
transiently in memory, never written to disk or printed. Run after the user
has finished signing in, not while credentials are being entered.

The dialog may also report recent-reading and full-preferred-shelf counts
("N개 수신 (1쪽)", N bounded 0..100) or the fixed failure marker
"library:unavailable". Only those bounded values leave this tool.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

CODE = r"favorites:(?:network|invalid_json|invalid_envelope|status_missing|status_non200|status_nonstandard|items_type|invalid_rows)(?::[0-9]{1,4})?"
LIBRARY_UNAVAILABLE = "library:unavailable"
# The checked screen may run up to three sequential server loads (~15s each);
# poll long enough for all of them while keeping the wait bounded.
MAX_DIAGNOSTIC_WAIT_SECONDS = 60


def summarize(text):
    """Only fixed states, bounded counts, and generated diagnostics leave here."""
    result = {}
    lines = text.splitlines()
    if "로그인 상태: 서버에서 확인됨" in lines:
        result["login"] = "authenticated"
    elif text == "로그인이 필요합니다.":
        result["login"] = "unauthenticated"
    elif text in ("세션 상태를 확인하지 못했습니다.", "로그인 상태를 확인하지 못했습니다."):
        result["login"] = "unknown"
    for line in lines:
        recent = re.fullmatch(r"최근 본 작품: ([0-9]+)개 수신 \(1쪽\)", line)
        if recent and 0 <= int(recent[1]) <= 100:
            result["recent_count"] = int(recent[1])
        preferred = re.fullmatch(r"전체 선호작: ([0-9]+)개 수신 \(1쪽\)", line)
        if preferred and 0 <= int(preferred[1]) <= 100:
            result["preferred_count"] = int(preferred[1])
        if line == "최근 본 작품: " + LIBRARY_UNAVAILABLE:
            result["recent"] = LIBRARY_UNAVAILABLE
        if line == "전체 선호작: " + LIBRARY_UNAVAILABLE:
            result["preferred"] = LIBRARY_UNAVAILABLE
        value = line[len("최애 작품: "):] if line.startswith("최애 작품: ") else line
        if re.fullmatch(CODE, value):
            result["favorites"] = value
        count = re.fullmatch(r"([0-5])개 수신 \(최대 5개\)", value)
        if count:
            result["favorites_count"] = int(count[1])
    if text == "선호작을 불러오지 못했습니다.":
        result["favorites"] = "legacy_status_rejected"
    return result


class AccountUI:
    def __init__(self, adb, serial):
        if not re.fullmatch(r"emulator-[0-9]+", serial):
            raise ValueError("This helper is restricted to local emulators")
        self.adb = [adb, "-s", serial]

    def command(self, *args):
        process = subprocess.run(self.adb + list(args), stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, timeout=35)
        if process.returncode:
            raise RuntimeError("ADB command failed")
        return process.stdout.decode("utf-8", "replace")

    def screen(self):
        active = self.command("shell", "dumpsys", "activity", "activities")
        if not any("mResumedActivity:" in line and "me.crema.novelia/.MainActivity" in line
                   for line in active.splitlines()):
            raise RuntimeError("Native app is not in the foreground")
        xml = self.command("shell", "uiautomator", "dump", "/dev/tty")
        start, end = xml.find("<?xml"), xml.rfind("</hierarchy>")
        if start < 0 or end < 0 or end - start > 2_000_000:
            raise RuntimeError("UI structure unavailable")
        nodes = list(ET.fromstring(xml[start:end + len("</hierarchy>")]).iter("node"))
        if any(n.get("class", "").endswith("EditText") or n.get("password") == "true" for n in nodes):
            raise RuntimeError("Input screen present; no action taken")
        return nodes

    def tap(self, nodes, label):
        if label not in {"내서재", "계정 연동 검사", "확인"}:
            raise ValueError("Disallowed action")
        matches = [n for n in nodes if n.get("text") == label]
        if len(matches) != 1:
            raise RuntimeError("Expected control not found")
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", matches[0].get("bounds", ""))
        if not bounds:
            raise RuntimeError("Invalid control bounds")
        x1, y1, x2, y2 = map(int, bounds.groups())
        self.command("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    @staticmethod
    def read_result(nodes):
        for node in nodes:
            if node.get("resource-id") == "android:id/message":
                result = summarize(node.get("text", ""))
                if result:
                    return result
        return {}

    def run(self):
        nodes = self.screen()
        previous = self.read_result(nodes)
        if previous:
            self.tap(nodes, "확인")
            nodes = self.screen()
        if any(n.get("resource-id") == "android:id/message" for n in nodes):
            raise RuntimeError("Unrecognized dialog; no action taken")
        self.tap(nodes, "내서재")
        self.tap(self.screen(), "계정 연동 검사")
        deadline = time.monotonic() + MAX_DIAGNOSTIC_WAIT_SECONDS
        while time.monotonic() < deadline:
            time.sleep(1)
            result = self.read_result(self.screen())
            if result:
                return result
        raise RuntimeError("No recognized diagnostic result")


def self_test():
    assert summarize("로그인 상태: 서버에서 확인됨\n최애 작품: favorites:status_non200:403") == {
        "login": "authenticated", "favorites": "favorites:status_non200:403"}
    assert summarize("로그인 상태: 서버에서 확인됨\n최애 작품: 5개 수신 (최대 5개)") == {
        "login": "authenticated", "favorites_count": 5}
    for private in ("secret-cookie", "favorites:network:secret-cookie",
                    "private title", "favorites:status_non200:12345", "favorites:unknown:200"):
        assert not summarize(private)
        assert "secret" not in json.dumps(summarize("최애 작품: " + private))
    assert summarize("로그인이 필요합니다.") == {"login": "unauthenticated"}
    assert summarize("로그인 상태: 서버에서 확인됨\n최애 작품: 4개 수신 (최대 5개)\n"
                     "최근 본 작품: 12개 수신 (1쪽)\n전체 선호작: 0개 수신 (1쪽)") == {
        "login": "authenticated", "favorites_count": 4,
        "recent_count": 12, "preferred_count": 0}
    assert summarize("최근 본 작품: 3개 수신 (1쪽)\n전체 선호작: library:unavailable") == {
        "recent_count": 3, "preferred": "library:unavailable"}
    assert summarize("최근 본 작품: library:unavailable\n전체 선호작: library:unavailable") == {
        "recent": "library:unavailable", "preferred": "library:unavailable"}
    assert summarize("최근 본 작품: 100개 수신 (1쪽)\n전체 선호작: 100개 수신 (1쪽)") == {
        "recent_count": 100, "preferred_count": 100}
    for bad in ("최근 본 작품: -1개 수신 (1쪽)",
                "최근 본 작품: 101개 수신 (1쪽)",
                "최근 본 작품: 1000개 수신 (1쪽)",
                "최근 본 작품: 5개 수신 (2쪽)",
                "최근 본 작품: 5개 수신 (최대 5개)",
                "최근 본 작품: library:unavailable:secret",
                "최근 본 작품: favorites:network:secret-cookie",
                "전체 선호작: -1개 수신 (1쪽)",
                "전체 선호작: 101개 수신 (1쪽)",
                "전체 선호작: library:unavailable:secret",
                "전체 선호작: favorites:status_non200:12345",
                "최근 본 작품: secret-cookie",
                "전체 선호작: private title"):
        summary = summarize(bad)
        assert not {"recent_count", "preferred_count", "recent", "preferred"} & set(summary)
        assert "secret" not in json.dumps(summary)
        assert "private title" not in json.dumps(summary)
    mixed = summarize("로그인 상태: 서버에서 확인됨\n최근 본 작품: 3개 수신 (1쪽)\n"
                      "private title\n전체 선호작: library:unavailable:secret")
    assert mixed == {"login": "authenticated", "recent_count": 3}
    assert "secret" not in json.dumps(mixed)
    assert "private title" not in json.dumps(mixed)
    print("Safe account UI summary self-test: PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        try:
            print(json.dumps(AccountUI(args.adb, args.serial).run(), ensure_ascii=False))
        except Exception:
            # No exception messages: subprocess/XML failures could contain UI data.
            print('{"diagnostic":"unavailable; no raw UI or errors printed"}')
            raise SystemExit(1)
