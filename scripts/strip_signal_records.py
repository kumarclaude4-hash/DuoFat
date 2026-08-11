#!/usr/bin/env python3
"""
Regenerate app/libs/libsignal-client-0.54.1-stripped.jar — reproducibly.

Run from the repo root:
    python3 scripts/strip_signal_records.py          # regenerate + verify
    python3 scripts/strip_signal_records.py --check   # verify only, write nothing

WHY THIS EXISTS
libsignal-client-0.54.1.jar contains classes that extend java.lang.Record
(a Java 16 sealed class). When coreLibraryDesugaringEnabled=true and minSdk<34,
D8 must produce a "global synthetic" helper for Record desugaring.
The DexingNoClasspathTransform that processes external Maven JARs runs with
enableGlobalSynthetics=false, causing a fatal build error:
  "Attempt to create a global synthetic for 'Record desugaring' without a consumer"
The stripped JAR (which Gradle picks up as a local file, not a Maven artifact)
goes through a different transform path that does NOT have this restriction.
All removed classes are from the libsignal.net and libsignal.zkgroup.groupsend
packages, which DuoShield does not use.

WHY IT LOOKS LIKE THIS  (security finding SC-01, Critical)
This file is the documented provenance of the single most security-critical
binary in the product: `app/build.gradle` marks the upstream Maven artifact
`compileOnly` and ships `libs/libsignal-client-0.54.1-stripped.jar` as
`implementation`, so the classes users actually execute — the entire Signal
Protocol implementation — come from that local file. Gradle applies **no**
integrity checking to `files(...)` dependencies.

The previous version of this script could not reproduce the artifact it claimed
to produce. It declared a 6-entry STRIP set, but the committed JAR has 10
entries removed: `ChatService`, `Svr3`, `GroupSendEndorsementsResponse` (three
whole top-level classes) and `ChatService$InternalRequest` were also gone, with
no documented provenance. So the obvious integrity check — re-run the script,
compare hashes — failed on the *known-good* artifact, which meant no future
check could distinguish a legitimate update from a malicious swap. A single
commit replacing this blob with a modified `SessionCipher` or a key generator
that leaks entropy would have compromised every message in the product and
looked like a routine dependency bump in the PR list.

Three properties are now enforced here, and each one is load-bearing:

  1. STRIP lists all 10 entries actually removed, so the output is byte-identical
     to the JAR that ships today (verified: sha256 fa7d3afe…d89e3, 38,466,351 B).
     Stripping by *prefix* instead — the tempting "express the intent" refactor —
     would remove 37 entries and NOT reproduce the shipped artifact, so the
     explicit list is deliberate. Do not "simplify" it to a prefix match.
  2. The source JAR is fetched from Maven Central and its SHA-256 is checked
     against UPSTREAM_SHA256 before a single byte is copied. The old script
     scraped ~/.gradle/caches, which made the output depend on whatever happened
     to be sitting in a local cache — an attacker-writable path and an
     unauditable input.
  3. The finished output is hashed and compared to EXPECTED_OUTPUT_SHA256. A
     mismatch is a hard failure, so this script can never quietly emit a
     different artifact than the one recorded in the repo.

Changing the shipped JAR therefore requires updating EXPECTED_OUTPUT_SHA256 (and
`app/libs/libsignal-client-0.54.1-stripped.jar.sha256`) in the same commit, which
is the reviewable signal that was missing. `.github/workflows/ci.yml` runs
`--check` on every push and pull request, so the hash gate is enforced by a
failing build rather than by convention.

Note on ZIP determinism: entries are copied with their original ZipInfo (name,
date_time, compress_type, external_attr) and re-deflated at the same default
level, which is what makes the byte-identical reproduction above hold.
"""

import argparse
import hashlib
import io
import os
import sys
import urllib.request
import zipfile

VERSION = "0.54.1"

# The 10 entries actually absent from the shipped JAR, verified by an
# entry-by-entry diff against the upstream artifact (0 added, 0 CRC-changed:
# every retained class is byte-identical to upstream). The four marked
# "undocumented" were missing from this script's original 6-entry set — they are
# Signal-server networking/zkgroup classes DuoShield never calls, and removing an
# outer class alongside its nested Record subclasses is the natural fix when an
# inner-class-only strip still fails to dex.
STRIP = {
    "org/signal/libsignal/net/ChatService$DebugInfo.class",
    "org/signal/libsignal/net/ChatService$InternalRequest.class",              # was undocumented
    "org/signal/libsignal/net/ChatService$Request.class",
    "org/signal/libsignal/net/ChatService$Response.class",
    "org/signal/libsignal/net/ChatService$ResponseAndDebugInfo.class",
    "org/signal/libsignal/net/ChatService.class",                              # was undocumented
    "org/signal/libsignal/net/Svr3$RestoredSecret.class",
    "org/signal/libsignal/net/Svr3.class",                                     # was undocumented
    "org/signal/libsignal/zkgroup/groupsend/GroupSendEndorsementsResponse$ReceivedEndorsements.class",
    "org/signal/libsignal/zkgroup/groupsend/GroupSendEndorsementsResponse.class",  # was undocumented
}

# Upstream org.signal:libsignal-client:0.54.1 from Maven Central.
UPSTREAM_URL = (
    "https://repo1.maven.org/maven2/org/signal/libsignal-client/"
    f"{VERSION}/libsignal-client-{VERSION}.jar"
)
UPSTREAM_SHA256 = "9605b9c6ce51f13f9025ef1b7d3789426fefb9a023f4fc136a01af1ef4487d4a"
UPSTREAM_SIZE = 38482974

DEST = f"app/libs/libsignal-client-{VERSION}-stripped.jar"
DEST_SHA256_FILE = f"{DEST}.sha256"

# SHA-256 of the artifact that ships today. Regenerating from the pinned upstream
# JAR with the STRIP set above must produce exactly this.
EXPECTED_OUTPUT_SHA256 = "fa7d3afe9376ee83b0370bd16aff3083ea61a9ce131ee62773b48b35e6bd89e3"
EXPECTED_OUTPUT_SIZE = 38466351


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_upstream() -> bytes:
    """Download the upstream JAR and fail closed unless it matches the pin.

    Fetching over HTTPS from Maven Central and then verifying the content hash
    means neither a hostile mirror nor a poisoned local Gradle cache can change
    what goes into the shipped artifact.
    """
    local = os.environ.get("LIBSIGNAL_UPSTREAM_JAR")
    if local:
        # Escape hatch for offline/air-gapped runs. Still hash-verified below —
        # this changes where the bytes come from, never whether they are checked.
        print(f"Source : {local}  (LIBSIGNAL_UPSTREAM_JAR)")
        with open(local, "rb") as fh:
            data = fh.read()
    else:
        print(f"Source : {UPSTREAM_URL}")
        with urllib.request.urlopen(UPSTREAM_URL, timeout=300) as resp:
            data = resp.read()

    digest = sha256_bytes(data)
    if len(data) != UPSTREAM_SIZE or digest != UPSTREAM_SHA256:
        sys.exit(
            "FATAL: upstream libsignal JAR does not match its pin.\n"
            f"  expected sha256 {UPSTREAM_SHA256} ({UPSTREAM_SIZE} B)\n"
            f"  got      sha256 {digest} ({len(data)} B)\n"
            "Refusing to build the shipped crypto artifact from an unverified source."
        )
    print(f"         sha256 {digest} (matches pin)")
    return data


def build_stripped(upstream: bytes) -> bytes:
    """Produce the stripped JAR in memory. Fails if STRIP does not match reality."""
    out = io.BytesIO()
    removed, kept = [], 0

    with zipfile.ZipFile(io.BytesIO(upstream), "r") as z_in, \
            zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z_out:
        for item in z_in.infolist():
            if item.filename in STRIP:
                removed.append(item.filename)
            else:
                z_out.writestr(item, z_in.read(item.filename))
                kept += 1

    # If upstream ever renames or drops one of these, silently stripping fewer
    # entries would change the artifact without changing this script. Catch it.
    missing = STRIP - set(removed)
    if missing:
        sys.exit(
            "FATAL: STRIP lists entries not present in the upstream JAR:\n  "
            + "\n  ".join(sorted(missing))
            + "\nThe strip set and the upstream artifact have diverged."
        )

    print(f"Kept   : {kept} entries")
    print(f"Removed: {len(removed)} entries")
    for r in sorted(removed):
        print(f"  - {r}")
    return out.getvalue()


def assert_expected(data: bytes) -> None:
    digest = sha256_bytes(data)
    if len(data) != EXPECTED_OUTPUT_SIZE or digest != EXPECTED_OUTPUT_SHA256:
        sys.exit(
            "FATAL: regenerated JAR does not match the recorded expected hash.\n"
            f"  expected sha256 {EXPECTED_OUTPUT_SHA256} ({EXPECTED_OUTPUT_SIZE} B)\n"
            f"  got      sha256 {digest} ({len(data)} B)\n"
            "Either the strip set changed, or the toolchain no longer reproduces the\n"
            "shipped artifact. Do NOT ship this output until the difference is explained\n"
            "and EXPECTED_OUTPUT_SHA256 + the .sha256 file are updated in the same commit."
        )
    print(f"Output : sha256 {digest} ({len(data)} B) — matches recorded hash")


def cmd_check() -> int:
    """Verify the committed JAR against the recorded hash. No network, no writes.

    This is what CI runs on every push/PR: it is the gate that makes swapping the
    binary a build failure instead of an invisible commit.
    """
    if not os.path.exists(DEST):
        print(f"::error::{DEST} is missing — the app cannot ship without it.")
        return 1

    actual = sha256_file(DEST)
    size = os.path.getsize(DEST)
    print(f"Vendored: {DEST}")
    print(f"  sha256 {actual} ({size} B)")
    print(f"  expect {EXPECTED_OUTPUT_SHA256} ({EXPECTED_OUTPUT_SIZE} B)")

    ok = True
    if actual != EXPECTED_OUTPUT_SHA256 or size != EXPECTED_OUTPUT_SIZE:
        print(
            "::error::Vendored libsignal JAR does not match the hash recorded in "
            "scripts/strip_signal_records.py (SC-01). This file is the app's entire "
            "Signal Protocol implementation and ships to users. If the change is "
            "intentional, regenerate it with this script and update "
            "EXPECTED_OUTPUT_SHA256 and the .sha256 file in the same commit."
        )
        ok = False

    # The sidecar .sha256 must agree, so the two records cannot drift apart.
    if os.path.exists(DEST_SHA256_FILE):
        with open(DEST_SHA256_FILE, "r", encoding="utf-8") as fh:
            fields = fh.read().split()
        recorded = fields[0].strip().lower() if fields else ""
        if recorded != EXPECTED_OUTPUT_SHA256:
            print(
                f"::error::{DEST_SHA256_FILE} records {recorded}, which disagrees with "
                f"EXPECTED_OUTPUT_SHA256 {EXPECTED_OUTPUT_SHA256}."
            )
            ok = False
    else:
        print(f"::error::{DEST_SHA256_FILE} is missing — the expected hash must be recorded in-repo.")
        ok = False

    print("OK: vendored libsignal JAR matches its recorded hash." if ok else "FAILED")
    return 0 if ok else 1


def cmd_regenerate() -> int:
    upstream = fetch_upstream()
    data = build_stripped(upstream)
    assert_expected(data)

    os.makedirs(os.path.dirname(DEST), exist_ok=True)
    with open(DEST, "wb") as fh:
        fh.write(data)
    with open(DEST_SHA256_FILE, "w", encoding="utf-8") as fh:
        fh.write(f"{EXPECTED_OUTPUT_SHA256}  {os.path.basename(DEST)}\n")

    print(f"Wrote  : {DEST}")
    print(f"Wrote  : {DEST_SHA256_FILE}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--check",
        action="store_true",
        help="Verify the committed JAR against the recorded hash; write nothing.",
    )
    args = ap.parse_args()
    return cmd_check() if args.check else cmd_regenerate()


if __name__ == "__main__":
    sys.exit(main())
