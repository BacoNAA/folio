"""在专用模拟器复验阅读字号。传入设备序列号和最近记录中可见的唯一短正文。"""

import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def verify(serial, body, save_retry=False):
    def adb(*args):
        return subprocess.check_output(
            ["adb", "-s", serial, *args], encoding="utf-8", timeout=20
        ).strip()

    def width(bounds):
        return bounds[2] - bounds[0]

    def locate(label, matches=lambda bounds: True, *, checked=False):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
            document, end, _ = xml.partition("</hierarchy>")
            if end:
                for node in ET.fromstring(document + end).iter("node"):
                    if checked and all(node.get(k) != "true" for k in ("checked", "selected")):
                        continue
                    labels = node.iter("node") if checked else (node,)
                    if any(label in (n.get("text"), n.get("content-desc")) for n in labels):
                        bounds = tuple(map(int, re.findall(r"-?\d+", node.attrib["bounds"])))
                        if width(bounds) > 0 and bounds[3] > bounds[1] and matches(bounds):
                            return bounds
            time.sleep(0.2)
        raise AssertionError(f"未找到预期状态的可见控件 {label!r}")

    def tap(label):
        left, top, right, bottom = locate(label)
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def open_record():
        adb("shell", "am", "start", "-W", "-n", "com.baconaa.folio/.MainActivity")
        tap("记录")
        tap("最近记录")
        tap(body)
        locate("编辑")

    def set_scale(value):
        if value == "null":
            adb("shell", "settings", "delete", "system", "font_scale")
        else:
            adb("shell", "settings", "put", "system", "font_scale", value)

    if save_retry:
        adb("shell", "am", "force-stop", "com.baconaa.folio")
        open_record()
        normal = width(locate(body))
        tap("阅读设置")
        locate("跟随系统", checked=True)
        run_as = ("shell", "run-as", "com.baconaa.folio")
        mode = adb(*run_as, "stat", "-c", "%a", "shared_prefs")
        try:
            adb(*run_as, "chmod", format(int(mode, 8) & ~0o222, "o"), "shared_prefs")
            tap("放大正文")
            locate("字号未保存，请重新选择。")
            locate("跟随系统", checked=True)
        finally:
            adb(*run_as, "chmod", mode, "shared_prefs")
        tap("放大正文")
        large = width(locate(body, lambda bounds: width(bounds) > normal))
        adb("shell", "am", "force-stop", "com.baconaa.folio")
        open_record()
        locate(body, lambda bounds: width(bounds) == large)
        tap("阅读设置")
        tap("跟随系统")
        locate(body, lambda bounds: width(bounds) == normal)
        print(f"写入失败时保留跟随系统，同窗重试与冷启动后正文均为 {large}px，恢复为 {normal}px")
        return

    original_scale = adb("shell", "settings", "get", "system", "font_scale")
    try:
        if original_scale not in ("null", "1", "1.0"):
            set_scale("1.0")
        adb("shell", "am", "force-stop", "com.baconaa.folio")
        open_record()
        normal = width(locate(body))
        tap("阅读设置")
        tap("放大正文")
        large = width(locate(body, lambda bounds: width(bounds) > normal))
        first_pid = adb("shell", "pidof", "com.baconaa.folio")

        adb("shell", "am", "force-stop", "com.baconaa.folio")
        open_record()
        assert adb("shell", "pidof", "com.baconaa.folio") != first_pid, "应用进程未重启"
        locate(body, lambda bounds: width(bounds) == large)
        tap("阅读设置")
        tap("跟随系统")
        locate(body, lambda bounds: width(bounds) == normal)

        set_scale("1.6")
        scaled = width(locate(body, lambda bounds: width(bounds) > normal))
        locate("编辑")
        tap("阅读设置")
        tap("关闭")
        print(f"正文宽度(px) 跟随系统 {normal}，放大 {large}，冷启动 {large}，复原 {normal}，系统 1.6 倍 {scaled}")
    finally:
        set_scale(original_scale)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("body")
    parser.add_argument("--save-retry", action="store_true", help="在专用调试安装上验证写入失败及原地重试")
    args = parser.parse_args()
    verify(args.serial, args.body, args.save_retry)
