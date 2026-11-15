"""Capture real Atlas emulator screens and compare them with reviewed screenshot baselines.

Install Pillow for the compare command. Capture requires adb and a running emulator.
"""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def adb(serial, *args):
    command = ["adb", "-s", serial, *map(str, args)]
    for attempt in range(3):
        try:
            return subprocess.check_output(command, stderr=subprocess.PIPE, timeout=25)
        except subprocess.CalledProcessError as error:
            detail = error.stderr.decode(errors="replace")
            if attempt == 2 or not any(message in detail for message in (
                "daemon", "cannot connect", "device offline", "no devices", "not found"
            )):
                raise RuntimeError(f"{' '.join(command)}: {detail}") from error
            subprocess.run(["adb", "start-server"], check=False, timeout=20)
            time.sleep(1.2)


def screenshot(serial, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    image = adb(serial, "exec-out", "screencap", "-p")
    if not image.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("adb did not return a PNG")
    output.write_bytes(image)
    print(output, flush=True)


class Device:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output

    def adb(self, *args):
        return adb(self.serial, *args)

    def tap_xy(self, x, y):
        self.adb("shell", "input", "tap", x, y)
        time.sleep(.35)

    def nodes(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/atlas-flow-ui.xml")
        xml = self.adb("shell", "cat", "/sdcard/atlas-flow-ui.xml")
        return ET.fromstring(xml).iter("node")

    def tap(self, label, *, last=False):
        for _ in range(2):
            nodes = list(self.nodes())
            matches = [n for n in nodes if label in (n.get("text"), n.get("content-desc"))]
            if matches:
                bounds = list(map(int, re.findall(r"\d+", matches[-1 if last else 0].get("bounds"))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    self.tap_xy((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
                    return
            time.sleep(.40)
        raise RuntimeError(f"Element {label!r} missing. Visible labels: " +
                           " | ".join(n.get("text") or n.get("content-desc") for n in nodes
                                      if n.get("text") or n.get("content-desc")))

    def back(self):
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(.35)

    def snap(self, name):
        time.sleep(.55)
        screenshot(self.serial, self.output / f"{name}.png")

    def has(self, label):
        return any(label in (n.get("text"), n.get("content-desc")) for n in self.nodes())

    def resume(self):
        self.adb("shell", "am", "start", "-W", "-f", "0x34000000", "-n", "com.meetingatlas/.MainActivity",
                 "--ez", "com.meetingatlas.SHOW_DICTATION", "true")
        time.sleep(.65)

    def editor(self):
        self.adb("shell", "am", "start", "-W", "-n",
                 "com.meetingatlas.test/com.meetingatlas.fixtures.EditorFixtureActivity")
        self.tap("Second message")
        time.sleep(.5)

    def bubble(self):
        window = self.adb("shell", "dumpsys", "window", "windows").decode(errors="replace")
        for block in re.split(r"(?m)^\s*Window #", window):
            if re.match(r"^\d+ Window\{[^\n]* Atlas dictation\}:", block):
                match = re.search(r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block)
                if match:
                    return tuple(map(int, match.groups()))
        return None

    def recording(self):
        return b"isForeground=true" in self.adb(
            "shell", "dumpsys", "activity", "services", "com.meetingatlas/.dictation.DictationService")


def capture_suite(device):
    if not device.serial.startswith("emulator-"):
        raise RuntimeError("The scripted suite is intended for an isolated emulator.")
    device.resume()
    device.snap("home")
    device.tap("Dictionary")
    device.snap("dictionary")
    device.tap("Add a word")
    device.snap("dictionary-add")
    device.tap("Cancel")
    device.tap("Snippets")
    device.snap("snippets")
    device.tap("Add a snippet")
    device.snap("snippets-add")
    device.tap("Cancel")
    device.tap("Home", last=True)
    device.tap("View all")
    device.snap("history")
    device.tap("Back")
    device.tap("Settings")
    device.snap("settings")
    for label, file in (("Languages", "settings-language"), ("Bubble size", "settings-bubble"),
                        ("Bubble opacity", "settings-opacity"), ("Microphone", "settings-microphone"),
                        ("Privacy", "settings-privacy"), ("Service status", "settings-status"),
                        ("Speech provider", "settings-provider")):
        device.tap(label)
        device.snap(file)
        device.tap("Back")
    device.tap("Back")
    device.tap("Open menu")
    device.snap("drawer")
    device.tap("Report an issue")
    device.snap("report")
    device.tap("Cancel")
    device.tap("Open menu")
    device.tap("Account")
    device.snap("account")
    device.tap("Back")
    device.tap("Open menu")
    device.tap("Help")
    device.snap("help")
    device.tap("Set up dictation")
    for index, label in enumerate(("welcome", "intro", "language", "microphone",
                                   "overlay", "accessibility", "privacy", "practice", "done")):
        if label == "microphone" and device.has("Allow microphone"):
            device.snap("setup-microphone-request")
            device.tap("Allow microphone")
            device.tap("While using the app")
        if label == "overlay" and device.has("Open overlay settings"):
            device.snap("setup-overlay-request")
            device.tap("Open overlay settings")
            device.tap("Meeting Atlas")
            device.tap("Allow display over other apps")
            device.back()
            device.back()
        if label == "accessibility":
            if device.has("Review disclosure"):
                device.snap("setup-accessibility-request")
                device.tap("Review disclosure")
                device.snap("setup-disclosure")
                device.tap("Accept and continue")
            if device.has("Open accessibility settings"):
                device.tap("Open accessibility settings")
                device.tap("Atlas voice dictation")
                device.tap("Use Atlas voice dictation")
                device.tap("Allow")
                device.back()
                device.back()
        device.snap(f"setup-{label}")
        device.tap("Get started" if index == 0 else "Open Atlas" if label == "done" else "Next")


def capture_overlay(device):
    if not device.serial.startswith("emulator-"):
        raise RuntimeError("Use an isolated emulator already configured for capture-only QA.")
    device.editor()
    idle = device.bubble()
    if not idle:
        raise AssertionError("No Atlas bubble. Approve the Android permissions and focus a normal editor.")
    if b"mInputShown=true" not in device.adb("shell", "dumpsys", "input_method"):
        raise AssertionError("The system keyboard is not visible.")
    device.snap("overlay-idle")
    try:
        device.tap_xy((idle[0] + idle[2]) // 2, (idle[1] + idle[3]) // 2)
        time.sleep(.55)
        active = device.bubble()
        if not active or not device.recording() or active[2] - active[0] < 2 * (idle[2] - idle[0]):
            raise AssertionError("The bubble did not expand into the real microphone session.")
        device.snap("overlay-listening")
        (device.output / "overlay-frames.json").write_text(json.dumps({"idle": idle, "listening": active}, indent=2) + "\n")
        device.tap_xy(active[0] + (active[2] - active[0]) * 14 // 100, (active[1] + active[3]) // 2)
        if device.recording():
            time.sleep(.7)
        if device.recording():
            raise AssertionError("The Cancel button did not release the foreground microphone.")
        device.tap("Example password")
        if device.bubble():
            raise AssertionError("The bubble was exposed in a password input.")
        device.snap("overlay-protected")
        print("External editor: idle, keyboard coexistence, listening, cancel and password suppression verified.")
    finally:
        if device.recording():
            device.tap("Example password")


def compare(baselines, candidates, output, limit, delta, allow_subset):
    from PIL import Image, ImageChops, ImageOps
    failures = 0
    checked = 0
    if output:
        output.mkdir(parents=True, exist_ok=True)
    for file in sorted(baselines.glob("*.png")):
        target = candidates / file.name
        if not target.is_file():
            if not allow_subset:
                print(f"MISSING {target}")
                failures += 1
            continue
        expected = Image.open(file).convert("RGB")
        actual = Image.open(target).convert("RGB")
        if expected.size != actual.size:
            print(f"SIZE {file.name}: {expected.size} -> {actual.size}")
            failures += 1
            continue
        # Leave out variable system status icons; verify the actual app and navigation surface.
        box = (0, round(expected.height * .057), expected.width, expected.height)
        diff = ImageChops.difference(expected.crop(box), actual.crop(box))
        r, g, b = diff.split()
        mask = ImageChops.lighter(ImageChops.lighter(r, g), b).point(lambda value: 255 if value > delta else 0)
        score = mask.histogram()[255] * 100 / (mask.width * mask.height)
        checked += 1
        if score > limit:
            failures += 1
            if output:
                ImageOps.colorize(mask, "#161518", "#ff4975").save(output / file.name)
        print(f"{file.name}: {score:.2f}% changed" + (" FAIL" if score > limit else ""))
    if checked == 0:
        print("No matching screenshots were compared.")
    return int(failures != 0 or checked == 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    one = modes.add_parser("snap")
    one.add_argument("output", type=Path)
    one.add_argument("--serial", default="emulator-5562")
    suite = modes.add_parser("suite")
    suite.add_argument("output", type=Path)
    suite.add_argument("--serial", default="emulator-5562")
    live = modes.add_parser("overlay")
    live.add_argument("output", type=Path)
    live.add_argument("--serial", default="emulator-5562")
    check = modes.add_parser("compare")
    check.add_argument("baselines", type=Path)
    check.add_argument("candidates", type=Path)
    check.add_argument("--diff-dir", type=Path)
    check.add_argument("--max-changed", type=float, default=2.5)
    check.add_argument("--channel-delta", type=int, default=18)
    check.add_argument("--allow-subset", action="store_true")
    args = parser.parse_args()
    if args.mode == "snap":
        screenshot(args.serial, args.output)
    elif args.mode == "suite":
        capture_suite(Device(args.serial, args.output))
    elif args.mode == "overlay":
        capture_overlay(Device(args.serial, args.output))
    else:
        return compare(args.baselines, args.candidates, args.diff_dir,
                       args.max_changed, args.channel_delta, args.allow_subset)
    return 0


if __name__ == "__main__":
    sys.exit(main())
