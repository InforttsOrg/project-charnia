#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# Charnia release validation gatekeeper.
#
#   ./validate-release.sh              full gate
#   ./validate-release.sh --test-only  identical checks, CI-friendly flag
#
# There is no version bump here on purpose: the release version is owned by the
# Jenkins tag-driven plan in ci/jenkins-common.groovy (plan() -> otaBump()), which
# derives it from git tags and mobile/pubspec.yaml. Inventing a second version
# source in the gate is how .version/pubspec drift started elsewhere in the fleet.
#
# Steps: shell syntax -> py_compile -> workflow YAML -> mobile app manifest
# -> Flutter analyze/test -> version consistency -> CDN publisher fail-closed
# -> secret guard -> lint. Exits non-zero on the first real failure so a broken
# publisher, an unresolvable pubspec or a committed keystore can never be published.

PY_CMD="$(command -v python3 || true)"
if [ -z "$PY_CMD" ]; then
  echo "python3 not found; cannot verify release." >&2
  exit 1
fi

TEST_ONLY=0
case "${1:-}" in
  --test-only) TEST_ONLY=1 ;;
  "") ;;
  *) echo "validate-release.sh: unknown argument '$1' (expected --test-only)" >&2; exit 2 ;;
esac
# A stray second argument used to be ignored, so `--test-only <typo>` validated
# something other than what the caller asked for. Fail closed on it.
if [ "$#" -gt 1 ]; then
  echo "validate-release.sh: unexpected extra argument '$2' (only --test-only is accepted)" >&2
  exit 2
fi

echo "== shell syntax =="
bash -n validate-release.sh
[ -f dev.sh ] && bash -n dev.sh
# gradlew is a committed POSIX shell launcher that the mac agent invokes directly;
# a syntax error in it only shows up mid-build on the mac.
[ -f mobile/android/gradlew ] && bash -n mobile/android/gradlew
echo "  shell scripts parse"

echo "== py_compile =="
"$PY_CMD" -m py_compile ci/*.py

echo "== workflow YAML =="
# A typo in the job graph means the repo silently stops being validated at all,
# which is how the trivial "test -f README.md" check went unnoticed. This repo
# ships no .github/workflows yet, so the loop is a no-op today but enforces the
# invariant the moment one is added.
"$PY_CMD" - <<'PY'
import glob, sys
try:
    import yaml
except ImportError:
    print("  (PyYAML not installed - workflow parse skipped)")
    sys.exit(0)
paths = sorted(glob.glob(".github/workflows/*.yml") + glob.glob(".github/workflows/*.yaml"))
if not paths:
    print("  no .github/workflows present - skipped")
    sys.exit(0)
bad = []
for path in paths:
    with open(path) as f:
        try:
            spec = yaml.safe_load(f)
        except Exception as e:
            bad.append(f"{path}: {e}")
            continue
    # A workflow with no jobs is valid YAML that enforces nothing.
    if not isinstance(spec, dict) or not (spec.get("jobs") or spec.get(True)):
        bad.append(f"{path}: no 'jobs' mapping")
for line in bad:
    print(f"  {line}")
if bad:
    sys.exit(1)
print("  workflow files parse and declare jobs")
PY

echo "== mobile app manifest =="
# mobile/pubspec.yaml is release input: Jenkins plan() reads it for the Play
# version, and `flutter pub get` is what the whole Flutter stage stands on. A
# scaffold rewrite once left `shared_preferences:` with no constraint, which pub
# rejects outright (the app cannot resolve at all) and which nothing else in this
# gate would notice. Deliberately regex-based, not PyYAML: this guard must stay
# active even on a host without PyYAML.
if [ -f mobile/pubspec.yaml ]; then
"$PY_CMD" - <<'PY'
import glob, re, sys

SDK_PACKAGES = {
    "flutter", "flutter_test", "flutter_driver", "flutter_localizations",
    "flutter_web_plugins", "integration_test", "flutter_native_splash",
}
bad = []
declared = {}
self_name = None
with open("mobile/pubspec.yaml") as f:
    lines = [l.rstrip("\n") for l in f]


def _next_content(i):
    """Index of the next line that carries something, or len(lines)."""
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped and not stripped.startswith("#"):
            return i
        i += 1
    return len(lines)


section = None
i = 0
while i < len(lines):
    line = lines[i]
    i += 1
    if not line.strip() or line.lstrip().startswith("#"):
        continue
    if not line[:1].isspace():                          # top-level key
        key, _, rest = line.partition(":")
        section = key.strip()
        if section == "name":
            self_name = rest.strip().strip("'\"")
        continue
    if section not in ("dependencies", "dev_dependencies"):
        continue
    key, _, rest = line.partition(":")
    name, value = key.strip(), rest.strip()
    if not name or name in declared:
        continue
    if not value:
        # `foo:` on its own is a nested map (sdk:/path:/git:) ONLY when something
        # deeper follows it; otherwise it parses as null.
        j = _next_content(i)
        if j >= len(lines) or len(lines[j]) - len(lines[j].lstrip()) <= len(line) - len(line.lstrip()):
            value = "null"
    declared[name] = value
    # pub treats a null version constraint as a hard error, not as "any version".
    if value == "null":
        bad.append(f"mobile/pubspec.yaml: dependency '{name}' has no version "
                   f"constraint — 'flutter pub get' rejects it")

missing = []
for path in sorted(glob.glob("mobile/lib/**/*.dart", recursive=True)
                   + glob.glob("mobile/test/**/*.dart", recursive=True)):
    with open(path) as f:
        body = f.read()
    for pkg in set(re.findall(r"""package:([A-Za-z_][A-Za-z0-9_]*)/""", body)):
        if pkg in SDK_PACKAGES or pkg == self_name or pkg in declared:
            continue
        missing.append(f"{path}: imports 'package:{pkg}' which mobile/pubspec.yaml "
                       f"does not declare")
bad.extend(sorted(missing))

for line in bad:
    print(f"  {line}")
if bad:
    print(f"  {len(bad)} mobile manifest problem(s)")
else:
    print(f"  {len(declared)} dependencies declared, every package: import resolves")
sys.exit(1 if bad else 0)
PY
else
  echo "  (no mobile/pubspec.yaml - skipped)"
fi

echo "== Flutter app =="
if command -v flutter >/dev/null 2>&1; then
  echo "  pub get..."
  if ! ( cd mobile && flutter pub get >/dev/null 2>&1 ); then
    echo "validate-release.sh: Flutter dependency resolution failed! Release rejected." >&2
    exit 1
  fi
  echo "  analyze..."
  if ! ( cd mobile && flutter analyze --no-fatal-warnings --no-fatal-infos >/dev/null 2>&1 ); then
    echo "validate-release.sh: Flutter static analysis failed! Release rejected." >&2
    exit 1
  fi
  echo "  test..."
  if ! ( cd mobile && flutter test >/dev/null 2>&1 ); then
    echo "validate-release.sh: Flutter widget tests failed! Release rejected." >&2
    exit 1
  fi
  echo "  ✓ flutter analyze + widget tests verified"
else
  echo "  Flutter toolchain not found — skipping Flutter gate (headless/static environments)."
fi

echo "== version consistency =="
# .version, mobile/pubspec.yaml and the app version Charnia displays must not
# drift: OTA clients and the CDN manifest compare version codes, and a mismatch
# silently serves stale builds as current (or blocks updates entirely).
"$PY_CMD" - <<'PY'
import re, sys
try:
    import yaml
except ImportError:
    print("  (PyYAML not installed - version consistency skipped)")
    sys.exit(0)

with open(".version") as f:
    version = f.read().strip()
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    print(f"  .version is not MAJOR.MINOR.PATCH ('{version}')", file=sys.stderr)
    sys.exit(1)

with open("mobile/pubspec.yaml") as f:
    manifest = yaml.safe_load(f)
manifest_base = str(manifest.get("version", "")).split("+", 1)[0]

problems = []
if manifest_base != version:
    problems.append(f"mobile/pubspec.yaml version '{manifest_base}' != .version '{version}'")

main_dart = open("mobile/lib/main.dart").read()
if f"appVersion: '{version}'" not in main_dart:
    problems.append(f"mobile/lib/main.dart does not advertise appVersion '{version}'")

for p in problems:
    print(f"  {p}")
if problems:
    sys.exit(1)
print(f"  .version == mobile/pubspec.yaml == appVersion ({version})")
PY

echo "== CDN publisher fail-closed =="
# ci/upload_to_hf.py is the contract every installed OTA client reads. The
# scaffold's 140-line template is fail-open in three ways at once: every manifest
# pins Android versionCode 1, apk_url is advertised before the binary is uploaded,
# and a version-only publish OVERWRITES the live manifest — stripping the
# apk_url/sha256 that already-installed clients download from. Those are silent
# data loss, and nothing else in this gate would notice, so prove the guards hold.
#
# This is not hypothetical here: ci/jenkins-common.groovy's otaBump() calls
# publishHuggingFace() with an empty patch path whenever no .patch/.bin/.diff is
# found, which is the normal case for a repo with no OTA artifacts. Against the
# template that call overwrote the live manifest; against the guarded publisher
# it exits 2.
#
# Runs against a STUBBED huggingface_hub in a temp dir: the real CDN is never
# contacted, and --token short-circuits get_token() before it can read the live
# credential caches at ~/.cache/huggingface/token. NEVER run the real publisher
# here as a smoke test.
if [ -f ci/upload_to_hf.py ]; then
  if [ ! -f ci/requirements.txt ]; then
    echo "validate-release.sh: ci/upload_to_hf.py has no ci/requirements.txt pinning huggingface_hub" >&2
    exit 1
  fi
  "$PY_CMD" - <<'PY'
import os, pathlib, subprocess, sys, tempfile

src = pathlib.Path("ci/upload_to_hf.py").resolve()
bad = []

with tempfile.TemporaryDirectory() as td:
    td = pathlib.Path(td)
    script = td / "upload_to_hf.py"
    script.write_bytes(src.read_bytes())
    # A stub that FAILS LOUDLY if the publisher ever reaches the CDN: reaching it at
    # all means a guard above it failed to fire.
    (td / "huggingface_hub.py").write_text(
        "def _boom(*a, **k):\n"
        "    raise AssertionError('publisher reached the CDN: a fail-closed guard did not fire')\n"
        "class HfApi:\n"
        "    def __init__(self, *a, **k): _boom()\n"
        "def create_repo(*a, **k): _boom()\n"
        "def upload_file(*a, **k): _boom()\n"
    )
    env = dict(os.environ, PYTHONPATH=str(td))

    def run(label, extra):
        p = subprocess.run(
            [sys.executable, str(script), "--slug", "charnia",
             "--repo", "rttss/infortts-gate-must-not-be-used", "--token", "gate-dummy-token",
             *extra],
            capture_output=True, text=True, env=env, cwd=td, timeout=60)
        if p.returncode != 2:
            detail = (p.stderr or p.stdout).strip().splitlines()
            bad.append(f"{label}: expected exit 2, got {p.returncode}"
                       + (f" — {detail[-1]}" if detail else ""))
        elif "reached the CDN" in (p.stdout + p.stderr):
            bad.append(f"{label}: publisher contacted the CDN — the guards did not fire")
        return p

    apk = td / "gate.apk"
    apk.write_bytes(b"not a real apk")

    # 1. No artifact at all: would overwrite the live manifest with one carrying no
    #    apk_url/sha256, which is the silent data-loss case. Must refuse. This is the
    #    exact call shape otaBump() makes when no patch was found.
    run("publish with no --apk and no --patch", ["--version", "1.2.0"])
    # 2. A requested artifact that is not on disk: a manifest advertising a 404.
    run("publish with a non-existent --apk", ["--version", "1.2.0", "--apk",
                                              str(td / "does-not-exist.apk")])
    # 3. A version with no '+<build>' yields no positive Android versionCode, and
    #    versionCode 0 reads as a ROLLBACK to every OTA client that compares versions.
    run("publish an artifact with no build number", ["--version", "1.2.0", "--apk", str(apk)])

for line in bad:
    print(f"  {line}")
if bad:
    print(f"  {len(bad)} CDN publisher regression(s)")
else:
    print("  CDN publisher refuses artifact-less and unversioned publishes (3/3)")
sys.exit(1 if bad else 0)
PY
fi

echo "== secret guard =="
# mobile/android/key.properties and mobile/android/local.properties are ignored by
# mobile/android/.gitignore, so this guard is what stops the next scaffold commit
# from re-adding them. Untracking is not enough on its own.
if git rev-parse --git-dir >/dev/null 2>&1; then
    TRACKED="$(git ls-files)"
    LEAKED="$(printf '%s\n' "$TRACKED" | grep -E '(^|/)(key\.properties|local\.properties|google-services\.json|GoogleService-Info\.plist)$|(^|/)\.env$|\.(jks|keystore|p12|pem)$' || true)"
    if [ -n "$LEAKED" ]; then
        echo "validate-release.sh: credential files are tracked and must never be committed:" >&2
        printf '  %s\n' $LEAKED >&2
        exit 1
    fi
    CACHES="$(printf '%s\n' "$TRACKED" | grep -E '(^|/)(\.gradle|\.dart_tool|build)/|\.iml$' || true)"
    if [ -n "$CACHES" ]; then
        echo "validate-release.sh: build caches are tracked:" >&2
        printf '  %s\n' $CACHES >&2
        exit 1
    fi
    echo "  no tracked credential files or build caches"
else
    echo "  (not a git checkout - skipped)"
fi

echo "== lint =="
if "$PY_CMD" -c "import pyflakes" >/dev/null 2>&1; then
    "$PY_CMD" -m pyflakes ci
    echo "  pyflakes clean"
elif command -v pyflakes >/dev/null 2>&1; then
    pyflakes ci
    echo "  pyflakes clean"
else
    echo "  (pyflakes not installed - skipped)"
fi

echo "===================================================="
if [ "$TEST_ONLY" = "1" ]; then
    echo "validate-release.sh: PASS (--test-only, no release action taken)"
else
    echo "validate-release.sh: PASS"
    echo "Release versioning is owned by the Jenkins tag plan (ci/jenkins-common.groovy)."
fi
echo "===================================================="
