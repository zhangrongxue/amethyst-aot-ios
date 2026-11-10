#!/usr/bin/env python3
"""Fetch a Minecraft: Java Edition client jar and its libraries from Mojang.

This is the build-time equivalent of the download the launcher performs on the
device: it resolves the version manifest, downloads ``client.jar`` and every
library that applies to the target OS/arch, and writes ``manifest.json`` next to
them so the reflection scan can report exactly what was analysed.

Usage:
    python scripts/fetch_minecraft.py --version 1.12.2 --out work/mc
    python scripts/fetch_minecraft.py --version latest-release --out work/mc --os osx --arch arm64
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.request

VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
UA = {"User-Agent": "mcjava-ios-build/1.0"}


def http_get(url: str, retries: int = 4) -> bytes:
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=120) as resp:
                return resp.read()
        except Exception as exc:  # noqa: BLE001 - network flakiness is expected
            last = exc
            if attempt + 1 < retries:
                continue
    raise RuntimeError(f"GET {url} failed: {last}")


def download(url: str, dest: str, sha1: str | None = None) -> None:
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        print(f"  cached {os.path.basename(dest)}")
        return
    data = http_get(url)
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise RuntimeError(f"sha1 mismatch for {url}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as fh:
        fh.write(data)
    print(f"  saved  {os.path.basename(dest)} ({len(data)} bytes)")


def resolve_version(version: str) -> dict:
    manifest = json.loads(http_get(VERSION_MANIFEST))
    if version in ("latest-release", "latest-snapshot"):
        key = "release" if version == "latest-release" else "snapshot"
        version = manifest["latest"][key]
    for entry in manifest["versions"]:
        if entry["id"] == version:
            return json.loads(http_get(entry["url"]))
    raise SystemExit(f"version {version!r} not found in Mojang manifest")


def rule_allows(rules: list | None, os_name: str, arch: str) -> bool:
    """Evaluate Mojang's library rules for the given platform."""
    if not rules:
        return True
    allowed = False
    for rule in rules:
        applies = True
        cond = rule.get("os") or {}
        if "name" in cond and cond["name"] != os_name:
            applies = False
        if "arch" in cond and cond["arch"] != arch:
            applies = False
        if applies:
            allowed = rule.get("action") == "allow"
    return allowed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="1.12.2")
    ap.add_argument("--out", default="work/mc")
    ap.add_argument("--os", default="osx", choices=["osx", "linux", "windows"])
    ap.add_argument("--arch", default="arm64", choices=["arm64", "x86_64", "x86"])
    ap.add_argument("--no-libraries", action="store_true")
    args = ap.parse_args()

    print(f"[fetch] resolving Minecraft {args.version}")
    version = resolve_version(args.version)
    version_id = version["id"]
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)

    client = version["downloads"]["client"]
    print(f"[fetch] client.jar -> {out}")
    download(client["url"], os.path.join(out, "client.jar"), client.get("sha1"))

    libraries = []
    if not args.no_libraries:
        print("[fetch] libraries")
        for lib in version.get("libraries", []):
            if not rule_allows(lib.get("rules"), args.os, args.arch):
                continue
            art = (lib.get("downloads") or {}).get("artifact")
            if not art:
                continue
            name = lib["name"]
            group, artifact, ver = (name.split(":") + ["", "", ""])[:3]
            fname = f"{artifact}-{ver}.jar"
            download(art["url"], os.path.join(out, "libs", fname), art.get("sha1"))
            libraries.append({"name": name, "path": f"libs/{fname}"})

    # Save the full version JSON too: the AOT bootstrap reads it from
    # <GAME_DIR>/versions/<id>/<id>.json, exactly like the real launcher.
    with open(os.path.join(out, f"{version_id}.json"), "w", encoding="utf-8") as fh:
        json.dump(version, fh, indent=2)
    print(f"[fetch] version json -> {version_id}.json")

    meta = {
        "version": version_id,
        "client": {"path": "client.jar", "size": client.get("size"), "sha1": client.get("sha1")},
        "libraries": libraries,
    }
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, indent=2)
    print(f"[fetch] done: 1 client jar + {len(libraries)} libraries in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
