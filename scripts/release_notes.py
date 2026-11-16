#!/usr/bin/env python3
"""Print the CHANGELOG.md section for one version (used as the GitHub release body)."""
import pathlib
import re
import sys

version = sys.argv[1].lstrip("v")
text = (pathlib.Path(__file__).resolve().parent.parent / "CHANGELOG.md").read_text(encoding="utf-8")
match = re.search(rf"^## v?{re.escape(version)}\s*$(.*?)(?=^## |\Z)", text, re.M | re.S)
body = match.group(1).strip() if match else f"Music Light Synk v{version}"
print(body)
print(
    "\n---\n**Install / update**\n"
    "- **TV / Android app:** download `music-light-synk.apk` below, then `adb install -r music-light-synk.apk`.\n"
    "- **Mac:** download `music-light-synk-mac.zip`, unzip, move *Music Light Synk* to Applications "
    "(first launch: System Settings → Privacy & Security → Open Anyway).\n"
    "- **iPhone:** download `music-light-synk-iphone-project.zip`, open `ios/MusicLightSynk.xcodeproj` in Xcode, "
    "pick your Apple ID under Signing, Run on the iPhone.\n"
    "- **Home Assistant:** HACS → Music Light Synk → Update (or copy `music_light_synk.zip` "
    "into `config/custom_components/`).\n"
)
