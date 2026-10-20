"""Version checks, artifact collection, and publication for the remote release workflow."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "release-assets"


def version():
    value = json.loads((ROOT / "native/tauri.conf.json").read_text(encoding="utf-8"))["version"]
    assert re.fullmatch(r"\d+\.\d+\.\d+", value), "Invalid release version"
    cargo = (ROOT / "native/Cargo.toml").read_text(encoding="utf-8")
    assert re.search(r'^version = "' + re.escape(value) + '"$', cargo, re.M)
    assert json.loads((ROOT / "ui/package.json").read_text(encoding="utf-8"))["version"] == value
    android = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")
    assert f"versionName '{value}'" in android, "Android version differs"
    return value


def collect(target, platform):
    build = ROOT / "native/target" / target / "release"
    ASSETS.mkdir(exist_ok=True)
    assert platform == "windows-x64", "Only Windows desktop releases are enabled"
    matches = list((build / "bundle" / "nsis").glob("*.exe"))
    assert len(matches) == 1, f"Expected one NSIS installer, found {matches}"
    shutil.copy2(matches[0], ASSETS / f"clipkeep-{version()}-windows-x64-setup.exe")
    shutil.copy2(build / "clipkeep.exe", ASSETS / f"clipkeep-{version()}-windows-x64-portable.exe")


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True).strip()


def publish():
    value = version()
    tag = f"v{value}"
    expected = ["windows-x64-setup.exe", "windows-x64-portable.exe", "android.apk", "android.aab"]
    allowed = {f"clipkeep-{value}-{name}" for name in expected} | {"android-signature.txt", "SHA256SUMS"}
    assert all(p.name in allowed for p in ASSETS.iterdir()), "Unexpected release artifact"
    for name in expected:
        path = ASSETS / f"clipkeep-{value}-{name}"
        assert path.is_file() and path.stat().st_size > 0, f"Missing {path}"
    checksums = []
    for path in sorted(ASSETS.iterdir()):
        if path.name != "SHA256SUMS":
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            checksums.append(f"{digest}  {path.name}\n")
    (ASSETS / "SHA256SUMS").write_text("".join(checksums))
    # Refuse to replace an already published version or move an existing tag.
    releases = json.loads(gh("api", "repos/{owner}/{repo}/releases", "--paginate"))
    assert not any(r["tag_name"] == tag for r in releases), f"Release {tag} already exists"
    refs = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/git/matching-refs/tags/{tag}"))
    sha = os.environ["GITHUB_SHA"]
    for ref in refs:
        if ref["ref"] == f"refs/tags/{tag}":
            assert ref["object"]["sha"] == sha, "Existing tag points to a different commit"
    if not any(ref["ref"] == f"refs/tags/{tag}" for ref in refs):
        gh("api", "repos/{owner}/{repo}/git/refs", "-f", f"ref=refs/tags/{tag}", "-f", f"sha={sha}")
    gh("release", "create", tag, "--verify-tag", "--draft", "--title", f"clipkeep {tag}",
       "--notes-file", str(ROOT / "RELEASE_NOTES.md"), *[str(p) for p in sorted(ASSETS.iterdir())])
    # Upload succeeds before making the release public.
    gh("release", "edit", tag, "--draft=false", "--latest")
    print(gh("release", "view", tag, "--json", "url", "--jq", ".url"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["version", "collect", "publish"])
    parser.add_argument("--target")
    parser.add_argument("--platform")
    args = parser.parse_args()
    if args.command == "version":
        print(f"version={version()}")
    elif args.command == "collect":
        collect(args.target, args.platform)
    else:
        publish()
