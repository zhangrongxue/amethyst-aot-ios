#!/usr/bin/env python3
"""Structurally verify an unsigned Amethyst IPA produced by the CI build.

Checks (all of them have caused real-world "installs but crashes" reports):
  * zip parses, Payload/<App>.app exists
  * Info.plist is present and contains CFBundleIdentifier / CFBundleExecutable
  * the main binary is a Mach-O arm64 executable
  * all four iOS Java runtimes are present with lib/server/libjvm.dylib
  * Frameworks/ contains the renderer/audio dylibs the app expects
  * no _CodeSignature directory (i.e. it really is unsigned)

Exit code is non-zero when a required check fails.

Usage: python scripts/verify_ipa.py artifacts/*.ipa [--json out.json]
"""

from __future__ import annotations

import argparse
import json
import plistlib
import struct
import sys
import zipfile

MACHO_MAGIC = {
    b"\xcf\xfa\xed\xfe": "Mach-O 64-bit little endian",
    b"\xce\xfa\xed\xfe": "Mach-O 32-bit little endian",
    b"\xca\xfe\xba\xbe": "Mach-O universal",
}

REQUIRED_JRE = ["java-8-openjdk", "java-17-openjdk", "java-21-openjdk", "java-25-openjdk"]

REQUIRED_FRAMEWORKS = [
    "liblwjgl.dylib",
    "libopenal.dylib",
    "libMoltenVK.dylib",
]


def check(ipa: str) -> dict:
    report: dict = {"ipa": ipa, "ok": True, "errors": [], "warnings": [], "info": {}}
    try:
        zf = zipfile.ZipFile(ipa)
    except Exception as exc:  # noqa: BLE001
        report["ok"] = False
        report["errors"].append(f"cannot open zip: {exc}")
        return report

    names = zf.namelist()
    report["info"]["entries"] = len(names)
    report["info"]["bytes"] = sum(i.file_size for i in zf.infolist())

    apps = sorted({n.split("/")[1] for n in names if n.startswith("Payload/") and len(n.split("/")) > 2})
    if not apps:
        report["ok"] = False
        report["errors"].append("no Payload/<App>.app directory found")
        return report
    if len(apps) > 1:
        report["warnings"].append(f"multiple apps in Payload: {apps}")
    app = apps[0]
    root = f"Payload/{app}"
    report["info"]["app"] = app

    # Info.plist
    plist_name = f"{root}/Info.plist"
    if plist_name not in names:
        report["ok"] = False
        report["errors"].append("missing Info.plist")
    else:
        try:
            info = plistlib.loads(zf.read(plist_name))
            report["info"]["bundleId"] = info.get("CFBundleIdentifier")
            report["info"]["executable"] = info.get("CFBundleExecutable")
            report["info"]["minimumOS"] = info.get("MinimumOSVersion")
            if not info.get("CFBundleIdentifier"):
                report["ok"] = False
                report["errors"].append("Info.plist has no CFBundleIdentifier")
            if not info.get("CFBundleExecutable"):
                report["ok"] = False
                report["errors"].append("Info.plist has no CFBundleExecutable")
        except Exception as exc:  # noqa: BLE001
            report["ok"] = False
            report["errors"].append(f"cannot parse Info.plist: {exc}")

    # Main binary
    exe = report["info"].get("executable") or "AngelAuraAmethyst"
    exe_path = f"{root}/{exe}"
    if exe_path not in names:
        report["ok"] = False
        report["errors"].append(f"missing main binary {exe_path}")
    else:
        head = zf.read(exe_path)[:4]
        kind = MACHO_MAGIC.get(head)
        if not kind:
            report["ok"] = False
            report["errors"].append(f"main binary is not Mach-O (magic={head!r})")
        else:
            report["info"]["binaryKind"] = kind
            # arm64 cputype (0x0100000c) appears in the header for thin arm64 builds
            data = zf.read(exe_path)[:32]
            if len(data) >= 8:
                cputype = struct.unpack("<i", data[4:8])[0]
                if cputype != 0x0100000C and head == b"\xcf\xfa\xed\xfe":
                    report["warnings"].append(f"main binary cputype={cputype:#x} (expected arm64 0x100000c)")

    # Java runtimes
    missing_jre = []
    for jre in REQUIRED_JRE:
        jvm = f"{root}/java_runtimes/{jre}/lib/server/libjvm.dylib"
        if jvm not in names:
            missing_jre.append(jre)
    if missing_jre:
        report["ok"] = False
        report["errors"].append(f"missing java runtime libjvm.dylib for: {missing_jre}")

    # Frameworks
    fw = {n.split("/")[-1] for n in names if n.startswith(f"{root}/Frameworks/") and "/" not in n[len(f"{root}/Frameworks/"):]}
    report["info"]["frameworks"] = len(fw)
    missing_fw = [f for f in REQUIRED_FRAMEWORKS if f not in fw]
    if missing_fw:
        report["warnings"].append(f"Frameworks missing: {missing_fw}")

    # Unsigned?
    signed = [n for n in names if "_CodeSignature" in n]
    report["info"]["codeSignatureEntries"] = len(signed)
    if signed:
        report["warnings"].append("IPA contains _CodeSignature entries (not strictly unsigned)")
    report["info"]["hasMobileProvision"] = any(n.endswith("embedded.mobileprovision") for n in names)

    zf.close()
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ipa", nargs="+")
    ap.add_argument("--json")
    args = ap.parse_args()

    reports = []
    for path in args.ipa:
        report = check(path)
        reports.append(report)

        print(f"IPA: {report['ipa']}")
        print(f"  size: {report['info'].get('bytes', 0) / 1024 / 1024:.1f} MiB, entries: {report['info'].get('entries')}")
        print(f"  app: {report['info'].get('app')} bundle={report['info'].get('bundleId')}")
        print(f"  binary: {report['info'].get('binaryKind')} executable={report['info'].get('executable')}")
        print(f"  frameworks: {report['info'].get('frameworks')}  signatures: {report['info'].get('codeSignatureEntries')}")
        for w in report["warnings"]:
            print(f"  WARN  {w}")
        for e in report["errors"]:
            print(f"  FAIL  {e}")
        print("RESULT:", "PASS" if report["ok"] else "FAIL")
        print()

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(reports if len(reports) > 1 else reports[0], fh, indent=2)

    return 0 if all(r["ok"] for r in reports) else 1


if __name__ == "__main__":
    sys.exit(main())
