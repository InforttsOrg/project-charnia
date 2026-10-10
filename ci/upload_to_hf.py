#!/usr/bin/env python3
"""
Infortts Hugging Face CDN Uploader for Jenkins CI/CD.
Uploads built APK binaries, OTA patches, and manifests directly to Hugging Face Dataset CDN (rttss/ota-patches).
Guarantees 0 bytes static binary accumulation on VPS infrastructure.
"""

import os
import sys
import json
import time
import hashlib
import argparse
import subprocess

def get_token(custom_token=None):
    if custom_token:
        return custom_token
    if os.environ.get("HF_TOKEN"):
        return os.environ["HF_TOKEN"]
    if os.environ.get("HUGGING_FACE_HUB_TOKEN"):
        return os.environ["HUGGING_FACE_HUB_TOKEN"]

    # Check standard local cache files
    paths = [
        os.path.expanduser("~/.cache/huggingface/token"),
        os.path.expanduser("~/.huggingface/token"),
        os.path.expanduser("~/.config/huggingface/token")
    ]
    for p in paths:
        if os.path.exists(p):
            with open(p, "r") as f:
                t = f.read().strip()
                if t:
                    return t
    return None

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser(description="Upload APKs and OTA patches to Hugging Face CDN.")
    parser.add_argument("--slug", required=True, help="App slug name (e.g. waptia, mitochondria)")
    parser.add_argument("--apk", help="Path to built release APK file")
    parser.add_argument("--patch", help="Path to OTA differential patch file")
    parser.add_argument("--version", default="1.0.0", help="Version name (e.g. 1.2.0 or 2.03.01+20301)")
    parser.add_argument("--version-code", type=int, help="Android Version code (default: build number from --version '+<build>')")
    parser.add_argument("--track", default="internal", help="Distribution track (internal, alpha, beta, production)")
    parser.add_argument("--repo", default="rttss/ota-patches", help="Hugging Face Dataset repo ID")
    parser.add_argument("--token", help="Hugging Face write token")

    args = parser.parse_args()

    # A requested artifact that is not on disk must abort the publish: the manifest is
    # the contract OTA clients read, and a manifest advertising a URL whose binary was
    # never uploaded turns every client update into a 404. Fail before touching the CDN.
    for flag, path in (("--apk", args.apk), ("--patch", args.patch)):
        if path and not os.path.isfile(path):
            print(f"[ERROR] {flag} '{path}' does not exist — refusing to publish a manifest "
                  f"that points at a missing artifact.", file=sys.stderr)
            sys.exit(2)

    # Refuse a publish that carries no artifact at all. Such a manifest contains only
    # slug/version, and uploading it OVERWRITES the live manifest on the CDN, stripping
    # the apk_url/sha256/size_bytes that installed clients are currently downloading
    # from — the manifest is the entire contract, so a version-only publish is silent
    # data loss, not a no-op. otaBump() lands here whenever no .patch/.bin/.diff was
    # found, which is the normal case for a repo with no OTA artifacts.
    if not args.apk and not args.patch:
        print("[ERROR] Neither --apk nor --patch supplied — publishing a manifest with no "
              "artifact would strip the live apk_url/sha256 from the CDN manifest. "
              "Nothing was published.", file=sys.stderr)
        sys.exit(2)

    if args.version_code is None:
        # ci/jenkins-common.groovy passes only --version ("<base>+<build>"); deriving the
        # code from the build suffix keeps the manifest consistent instead of pinning 1.
        parts = args.version.split("+", 1)
        try:
            args.version_code = int(parts[1]) if len(parts) > 1 else 0
        except ValueError:
            print(f"[ERROR] Cannot derive version code from --version '{args.version}' "
                  f"(expected '<base>+<build>'); pass --version-code explicitly.", file=sys.stderr)
            sys.exit(2)

    # Android rejects versionCode 0, and a manifest that drops back to 0 is read as a
    # rollback by every OTA client that compares versions. Refuse rather than publish.
    if args.version_code <= 0:
        print(f"[ERROR] --version '{args.version}' yields version code {args.version_code}; "
              "an Android versionCode must be a positive integer. Pass '<base>+<build>' "
              "or --version-code N explicitly. Nothing was published.", file=sys.stderr)
        sys.exit(2)

    token = get_token(args.token)

    if not token:
        print("[ERROR] No Hugging Face authentication token found in environment or credentials cache.", file=sys.stderr)
        sys.exit(1)

    try:
        from huggingface_hub import HfApi, create_repo
    except ImportError:
        print("[INFO] Installing huggingface_hub...")
        # ci/requirements.txt pins the range; fall back to the bare package if the repo
        # copy is ever removed. Argument-list form: sys.executable is a path we do not
        # control and os.system would hand it to a shell.
        req = os.path.join(os.path.dirname(os.path.abspath(__file__)), "requirements.txt")
        target = ["-r", req] if os.path.isfile(req) else ["huggingface_hub"]
        if subprocess.run([sys.executable, "-m", "pip", "install", "-q"] + target).returncode != 0:
            print("[ERROR] Failed to install huggingface_hub — cannot publish to the CDN.",
                  file=sys.stderr)
            print("[ERROR] If pip reports an 'externally managed environment' (PEP 668), "
                  "install into a virtualenv and run with that interpreter: "
                  "'python3 -m venv .venv && .venv/bin/pip install -r ci/requirements.txt'.",
                  file=sys.stderr)
            sys.exit(1)
        try:
            from huggingface_hub import HfApi, create_repo
        except ImportError:
            print("[ERROR] huggingface_hub is still not importable after install — "
                  "cannot publish to the CDN.", file=sys.stderr)
            sys.exit(1)

    # Every network failure below must be loud and non-zero. Letting the SDK raise would
    # dump a traceback and, worse, make a partially published release look like a pass.
    try:
        publish(args, token, HfApi, create_repo)
    except Exception as e:
        print(f"[ERROR] CDN publish failed: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)


def publish(args, token, HfApi, create_repo):
    api = HfApi(token=token)
    repo_id = args.repo

    # Ensure dataset repo exists
    try:
        create_repo(repo_id=repo_id, repo_type="dataset", token=token, exist_ok=True)
    except Exception as e:
        print(f"[WARN] Repository check: {e}")

    slug = args.slug.strip().lower()
    manifest = {
        "slug": slug,
        "package_name": f"com.infortts.{slug}",
        "version_name": args.version,
        "version_code": args.version_code,
        "track": args.track,
        "updated_at": int(time.time()),
        "timestamp_iso": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    }

    # 1. Upload APK if provided (apk_url is added to the manifest only once the binary
    #    is really on the CDN, so a manifest never advertises a dead download URL)
    if args.apk:
        apk_size = os.path.getsize(args.apk)
        apk_hash = sha256_file(args.apk)
        manifest["size_bytes"] = apk_size
        manifest["sha256"] = apk_hash

        print(f"[HF CDN] Uploading {slug}.apk ({apk_size / (1024*1024):.2f} MB, SHA256: {apk_hash[:12]}...)")
        api.upload_file(
            path_or_fileobj=args.apk,
            path_in_repo=f"{slug}/{slug}.apk",
            repo_id=repo_id,
            repo_type="dataset",
            commit_message=f"CI: Release APK {slug} v{args.version} [{args.track}]"
        )
        # Only now is the download URL real.
        manifest["apk_url"] = f"https://huggingface.co/datasets/{repo_id}/resolve/main/{slug}/{slug}.apk"
        print(f"[HF CDN] APK Live CDN: {manifest['apk_url']}")

    # 2. Upload Patch if provided
    if args.patch:
        patch_name = os.path.basename(args.patch)
        patch_size = os.path.getsize(args.patch)
        patch_hash = sha256_file(args.patch)
        print(f"[HF CDN] Uploading patch {patch_name} ({patch_size / 1024:.2f} KB)...")
        api.upload_file(
            path_or_fileobj=args.patch,
            path_in_repo=f"{slug}/patches/{patch_name}",
            repo_id=repo_id,
            repo_type="dataset",
            commit_message=f"CI: OTA Patch {slug} v{args.version}"
        )
        # As with the APK: the manifest only advertises the URL once the file is up.
        manifest["patch"] = {
            "filename": patch_name,
            "size_bytes": patch_size,
            "sha256": patch_hash,
            "url": f"https://huggingface.co/datasets/{repo_id}/resolve/main/{slug}/patches/{patch_name}"
        }

    # 3. Upload Manifest
    manifest_bytes = json.dumps(manifest, indent=2).encode("utf-8")
    api.upload_file(
        path_or_fileobj=manifest_bytes,
        path_in_repo=f"{slug}/manifest.json",
        repo_id=repo_id,
        repo_type="dataset",
        commit_message=f"CI: Update manifest for {slug} v{args.version}"
    )
    print(f"[HF CDN] Manifest Live: https://huggingface.co/datasets/{repo_id}/raw/main/{slug}/manifest.json")
    print(json.dumps(manifest, indent=2))

if __name__ == "__main__":
    main()
