#!/usr/bin/env python3
"""Build steps for the iPhone App Store upload in .github/workflows/ios-app-store.yml.

  write-key       Save the App Store Connect API key from APP_STORE_CONNECT_PRIVATE_KEY (.p8 text or base64 of it).
  pick-xcode      Print the newest non-beta Xcode on the runner, or the one asked for (Xcode 26 or later).
  sign-archive    Ad hoc sign an unsigned Xcode archive with the Release entitlements written out in full, so the App
                  Store export keeps Associated Domains, Sign in with Apple and the Keychain group.
  export-options  Write the ExportOptions.plist for an App Store Connect export or upload.
  check-ipa       Check an exported .ipa: version, build, distribution signature and entitlements.
  attach-build    Wait for an uploaded build to finish processing, then put it on the App Store version page and set
                  the page's version number (adding the next version when the current one is already live). It never
                  submits anything for review.

Only the standard library is needed, except attach-build, which signs API tokens with `cryptography`.
"""

import argparse
import base64
import binascii
import glob
import json
import os
import plistlib
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from version import read_version


ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "iosApp/CatchCheckNZ.xcodeproj/project.pbxproj"
RELEASE_ENTITLEMENTS = ROOT / "iosApp/CatchCheckNZ/Resources/CatchCheckNZRelease.entitlements"
BUNDLE_ID = "nz.fishingnz.catchcheck"
API = "https://api.appstoreconnect.apple.com"
MINIMUM_XCODE = 26  # Apple's upload requirement since 28 April 2026

KEY_ID = "APP_STORE_CONNECT_KEY_ID"
ISSUER_ID = "APP_STORE_CONNECT_ISSUER_ID"
PRIVATE_KEY = "APP_STORE_CONNECT_PRIVATE_KEY"

# appVersionState values (appStoreState on older records).
EDITABLE_STATES = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY"}
IN_FLIGHT_STATES = {
    "WAITING_FOR_REVIEW", "IN_REVIEW", "READY_FOR_REVIEW", "WAITING_FOR_EXPORT_COMPLIANCE", "ACCEPTED",
    "PENDING_DEVELOPER_RELEASE", "PENDING_APPLE_RELEASE", "PROCESSING_FOR_DISTRIBUTION",
}


class StepError(Exception):
    """A problem the person running the workflow has to fix; printed without a traceback."""


# --- API key -----------------------------------------------------------------------------------------------------


def private_key_text(value):
    """Accept the .p8 file's text or base64 of it, as pasted into a repository secret."""
    value = (value or "").strip()
    if "BEGIN PRIVATE KEY" not in value:
        try:
            value = base64.b64decode("".join(value.split()), validate=True).decode().strip()
        except (binascii.Error, UnicodeDecodeError, ValueError):
            value = ""
    if not (value.startswith("-----BEGIN PRIVATE KEY-----") and value.endswith("-----END PRIVATE KEY-----")):
        raise StepError(f"{PRIVATE_KEY} must be the AuthKey_….p8 file's contents (or base64 of them)")
    return value + "\n"


def credentials(environ=os.environ):
    missing = [name for name in (KEY_ID, ISSUER_ID, PRIVATE_KEY) if not environ.get(name, "").strip()]
    if missing:
        raise StepError("Missing required repository secret: " + ", ".join(missing))
    key_id, issuer_id = environ[KEY_ID].strip(), environ[ISSUER_ID].strip()
    if not re.fullmatch(r"[A-Z0-9]{10}", key_id):
        raise StepError(f"{KEY_ID} should be the 10-character Key ID shown next to the key in App Store Connect")
    if not re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", issuer_id.lower()):
        raise StepError(f"{ISSUER_ID} should be the Issuer ID (a UUID) shown above the keys in App Store Connect")
    return key_id, issuer_id, private_key_text(environ[PRIVATE_KEY])


def write_key(out, environ=os.environ):
    key_id, _, text = credentials(environ)
    out = Path(out)
    if out.name != f"AuthKey_{key_id}.p8":
        raise StepError(f"xcodebuild expects the key file to be named AuthKey_{key_id}.p8")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.touch(mode=0o600)
    out.write_text(text)
    return out


# --- Xcode -------------------------------------------------------------------------------------------------------


def pick_xcode(paths, wanted=""):
    """Pick /Applications/Xcode_<version>.app: the requested version, else the newest release (never a beta)."""
    def version(path):
        return tuple(int(n) for n in re.findall(r"\d+", Path(path).name))

    releases = [p for p in paths if re.fullmatch(r"Xcode_[\d.]+(_Release_Candidate)?\.app", Path(p).name)]
    if wanted:
        matches = [p for p in releases if Path(p).name in (f"Xcode_{wanted}.app", f"Xcode_{wanted}_Release_Candidate.app")]
        if not matches:
            raise StepError(f"Xcode {wanted} is not installed on this runner; installed: "
                            + ", ".join(sorted(Path(p).name for p in paths)))
        chosen = matches[0]
    elif releases:
        chosen = max(releases, key=version)
    else:
        raise StepError("No released Xcode found in /Applications")
    if version(chosen)[0] < MINIMUM_XCODE:
        raise StepError(f"App Store uploads need Xcode {MINIMUM_XCODE} or later; picked {Path(chosen).name}")
    return chosen


def team_id(project=PROJECT):
    teams = set(re.findall(r"DEVELOPMENT_TEAM = (\w+);", Path(project).read_text()))
    if len(teams) != 1:
        raise StepError(f"Expected one DEVELOPMENT_TEAM in the Xcode project; found {sorted(teams) or 'none'}")
    return teams.pop()


# --- Signing -----------------------------------------------------------------------------------------------------


def expand(value, team, bundle_id):
    """Replace the build settings Xcode would substitute in an .entitlements file."""
    if isinstance(value, str):
        for name, replacement in (
            ("AppIdentifierPrefix", f"{team}."),
            ("TeamIdentifierPrefix", f"{team}."),
            ("CFBundleIdentifier", bundle_id),
            ("PRODUCT_BUNDLE_IDENTIFIER", bundle_id),
            ("DEVELOPMENT_TEAM", team),
        ):
            value = value.replace(f"$({name})", replacement)
        if "$(" in value:
            raise StepError(f"Unknown build setting in entitlement value {value!r}")
        return value
    if isinstance(value, list):
        return [expand(item, team, bundle_id) for item in value]
    if isinstance(value, dict):
        return {key: expand(item, team, bundle_id) for key, item in value.items()}
    return value


def signing_entitlements(source, team, bundle_id):
    """What the signature must carry for the export to request a matching App Store profile."""
    entitlements = expand(dict(source), team, bundle_id)
    entitlements.pop("get-task-allow", None)
    entitlements["application-identifier"] = f"{team}.{bundle_id}"
    entitlements["com.apple.developer.team-identifier"] = team
    return entitlements


def entitlement_problems(expected, actual):
    """Entitlements missing from or different in a signed bundle (extra ones from Apple's profile are fine)."""
    problems = [f"{key}: expected {value!r}, found {actual.get(key)!r}"
                for key, value in expected.items() if actual.get(key) != value]
    if actual.get("get-task-allow"):
        problems.append("get-task-allow is set (a development signature)")
    return problems


def bundle_info(bundle):
    with open(Path(bundle) / "Info.plist", "rb") as file:
        return plistlib.load(file)


def signed_entitlements(bundle):
    result = subprocess.run(["codesign", "-d", "--entitlements", "-", "--xml", str(bundle)],
                            check=True, capture_output=True)
    start = result.stdout.find(b"<?xml")
    return plistlib.loads(result.stdout[start:]) if start >= 0 else {}


def codesign(bundle, entitlements=None):
    command = ["codesign", "--force", "--sign", "-", "--timestamp=none"]
    with tempfile.NamedTemporaryFile(suffix=".entitlements") as file:
        if entitlements is not None:
            plistlib.dump(entitlements, file)
            file.flush()
            command += ["--generate-entitlement-der", "--entitlements", file.name]
        subprocess.run(command + [str(bundle)], check=True)
    if entitlements is not None:
        problems = entitlement_problems(entitlements, signed_entitlements(bundle))
        if problems:
            raise StepError(f"{Path(bundle).name} lost entitlements when signed: " + "; ".join(problems))


def one(paths, what):
    paths = sorted(paths)
    if len(paths) != 1:
        raise StepError(f"Expected one {what}; found {len(paths)}")
    return paths[0]


def sign_archive(archive, team, entitlements_path=RELEASE_ENTITLEMENTS):
    app = one(Path(archive).glob("Products/Applications/*.app"), "app in the archive")
    app_id = bundle_info(app)["CFBundleIdentifier"]
    if app_id != BUNDLE_ID:
        raise StepError(f"The archive holds {app_id}, not {BUNDLE_ID}")
    for nested in sorted(app.glob("Frameworks/*")):
        codesign(nested)
    for extension in sorted(app.glob("PlugIns/*.appex")) + sorted(app.glob("Extensions/*.appex")):
        extension_id = bundle_info(extension)["CFBundleIdentifier"]
        if not extension_id.startswith(app_id + "."):
            raise StepError(f"{extension.name} has bundle ID {extension_id}, outside {app_id}")
        codesign(extension, signing_entitlements({}, team, extension_id))
        print(f"Signed {extension.name} ({extension_id})")
    with open(entitlements_path, "rb") as file:
        codesign(app, signing_entitlements(plistlib.load(file), team, app_id))
    print(f"Signed {app.name} ({app_id}) with {Path(entitlements_path).name}")


def export_options(destination, team):
    return {
        "method": "app-store-connect",
        "destination": destination,
        "signingStyle": "automatic",
        "teamID": team,
        "uploadSymbols": True,
        "manageAppVersionAndBuildNumber": False,
    }


def check_ipa(ipa, team, version, build, entitlements_path=RELEASE_ENTITLEMENTS):
    with tempfile.TemporaryDirectory() as folder:
        subprocess.run(["unzip", "-q", str(ipa), "-d", folder], check=True)
        app = one(Path(folder).glob("Payload/*.app"), "app in the .ipa")
        bundles = [app] + sorted(app.glob("PlugIns/*.appex")) + sorted(app.glob("Extensions/*.appex"))
        problems = []
        for bundle in bundles:
            info = bundle_info(bundle)
            found = (info.get("CFBundleShortVersionString"), info.get("CFBundleVersion"))
            if found != (version, str(build)):
                problems.append(f"{bundle.name} is {found[0]} ({found[1]}), expected {version} ({build})")
            details = subprocess.run(["codesign", "-dvv", str(bundle)], capture_output=True, text=True).stderr
            distribution = "Authority=Apple Distribution" in details or "Authority=iPhone Distribution" in details
            if f"TeamIdentifier={team}" not in details or not distribution:
                problems.append(f"{bundle.name} is not signed for App Store distribution by team {team}")
        with open(entitlements_path, "rb") as file:
            expected = signing_entitlements(plistlib.load(file), team, BUNDLE_ID)
        problems += [f"{app.name}: {problem}" for problem in entitlement_problems(expected, signed_entitlements(app))]
    if problems:
        raise StepError("The exported package is not ready for App Store Connect:\n  " + "\n  ".join(problems))
    print(f"{Path(ipa).name}: {version} ({build}), App Store signature for team {team}, "
          f"{len(expected)} entitlements as in {Path(entitlements_path).name}")


# --- App Store Connect API ---------------------------------------------------------------------------------------


class ApiError(StepError):
    def __init__(self, method, path, status, body):
        details = "; ".join(
            " — ".join(part for part in (error.get("title"), error.get("detail")) if part)
            for error in (body or {}).get("errors", [])
        ) or "no details"
        super().__init__(f"App Store Connect {method} {path} failed with HTTP {status}: {details}")
        self.status = status


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


class AppStoreConnect:
    """A small App Store Connect API client: a fresh ES256 token for every request."""

    def __init__(self, key_id, issuer_id, private_key, clock=time.time, sleep=time.sleep):
        from cryptography.hazmat.primitives import serialization

        self.key_id, self.issuer_id, self.clock, self.sleep = key_id, issuer_id, clock, sleep
        self.key = serialization.load_pem_private_key(private_key.encode(), password=None)

    def token(self):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

        now = int(self.clock())
        header = {"alg": "ES256", "kid": self.key_id, "typ": "JWT"}
        claims = {"iss": self.issuer_id, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"}
        signing_input = ".".join(b64url(json.dumps(part, separators=(",", ":")).encode()) for part in (header, claims))
        r, s = decode_dss_signature(self.key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256())))
        return signing_input + "." + b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big"))

    def send(self, method, url, body):
        request = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode())
        request.add_header("Authorization", "Bearer " + self.token())
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                text = response.read()
                return response.status, json.loads(text) if text else None
        except urllib.error.HTTPError as error:
            text = error.read()
            try:
                return error.code, json.loads(text) if text else None
            except ValueError:
                return error.code, None

    def request(self, method, path, params=None, body=None, attempts=4):
        """Retries network errors and Apple's 429/5xx answers, except a POST that may already have happened."""
        url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
        for attempt in range(1, attempts + 1):
            try:
                status, payload = self.send(method, url, body)
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                if method == "POST" or attempt == attempts:
                    raise StepError(f"App Store Connect {method} {path} could not be reached: {error}") from error
                print(f"App Store Connect could not be reached ({error}); trying again")
            else:
                retry = status == 429 or (status >= 500 and method != "POST")
                if status < 400:
                    return payload
                if not retry or attempt == attempts:
                    raise ApiError(method, path, status, payload)
                print(f"App Store Connect answered HTTP {status}; trying again")
            self.sleep(10 * 2 ** (attempt - 1))


def version_state(version):
    attributes = version["attributes"]
    return attributes.get("appVersionState") or attributes.get("appStoreState") or ""


def choose_version(versions):
    """("update", version) for the version page being prepared, ("create", None) when the last one is live, or
    ("wait", version) while a version is with App Review or waiting to be released."""
    newest_first = sorted(versions, key=lambda v: v["attributes"].get("createdDate") or "", reverse=True)
    for version in newest_first:
        if version_state(version) in EDITABLE_STATES:
            return "update", version
    for version in newest_first:
        if version_state(version) in IN_FLIGHT_STATES:
            return "wait", version
    return "create", None


def find_app(api, bundle_id):
    apps = api.request("GET", "/v1/apps", {"filter[bundleId]": bundle_id, "fields[apps]": "bundleId,name"})["data"]
    for app in apps:
        if app["attributes"]["bundleId"] == bundle_id:
            return app
    raise StepError(f"No App Store Connect app with bundle ID {bundle_id} is visible to this API key")


def wait_for_build(api, app_id, version, build, wait_minutes, poll_seconds=30, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + wait_minutes * 60
    params = {
        "filter[app]": app_id,
        "filter[version]": str(build),
        "filter[preReleaseVersion.version]": version,
        "filter[preReleaseVersion.platform]": "IOS",
        "fields[builds]": "version,processingState,expired",
    }
    last = None
    while True:
        builds = api.request("GET", "/v1/builds", params)["data"]
        found = next((b for b in builds if b["attributes"]["version"] == str(build)), None)
        state = found["attributes"]["processingState"] if found else "NOT_FOUND"
        if state != last:
            print(f"Build {version} ({build}): {state.replace('_', ' ').lower()}")
            last = state
        if state == "VALID":
            if found["attributes"].get("expired"):
                raise StepError(f"Build {version} ({build}) has expired; upload a new build")
            return found
        if state in ("FAILED", "INVALID"):
            raise StepError(f"Apple could not process build {version} ({build}) ({state}); check the email "
                            "App Store Connect sent about it, fix it and upload a new build")
        if clock() >= deadline:
            raise StepError(f"Build {version} ({build}) is still {state.replace('_', ' ').lower()} after "
                            f"{wait_minutes} minutes; run the workflow again later with action 'attach'")
        sleep(poll_seconds)


def attach_build(api, version, build, wait_minutes, bundle_id=BUNDLE_ID, sleep=time.sleep, clock=time.monotonic):
    """Returns a one-line summary of what changed."""
    app = find_app(api, bundle_id)
    uploaded = wait_for_build(api, app["id"], version, build, wait_minutes, sleep=sleep, clock=clock)
    versions = api.request("GET", f"/v1/apps/{app['id']}/appStoreVersions", {
        "filter[platform]": "IOS",
        "fields[appStoreVersions]": "versionString,appVersionState,appStoreState,createdDate",
        "limit": "20",
    })["data"]
    action, page = choose_version(versions)
    if action == "wait":
        return (f"Build {version} ({build}) is processed and in TestFlight. It was not put on the App Store page: "
                f"version {page['attributes']['versionString']} is {version_state(page).replace('_', ' ').lower()}. "
                "Once that is released (or removed from review), run the workflow again with action 'attach'.")
    build_ref = {"type": "builds", "id": uploaded["id"]}
    if action == "create":
        api.request("POST", "/v1/appStoreVersions", body={"data": {
            "type": "appStoreVersions",
            "attributes": {"platform": "IOS", "versionString": version},
            "relationships": {"app": {"data": {"type": "apps", "id": app["id"]}}, "build": {"data": build_ref}},
        }})
        return (f"Added App Store version {version} with build {build}, ready for its What's New text and "
                "submission in App Store Connect.")
    previous = page["attributes"]["versionString"]
    if previous != version:
        api.request("PATCH", f"/v1/appStoreVersions/{page['id']}", body={"data": {
            "type": "appStoreVersions", "id": page["id"], "attributes": {"versionString": version},
        }})
    api.request("PATCH", f"/v1/appStoreVersions/{page['id']}/relationships/build", body={"data": build_ref})
    attached = api.request("GET", f"/v1/appStoreVersions/{page['id']}/build", {"fields[builds]": "version"})["data"]
    if not attached or attached["id"] != uploaded["id"]:
        raise StepError("App Store Connect accepted the change but the version page does not show the new build")
    renamed = f"from {previous} to {version}" if previous != version else f"{version} (unchanged)"
    return (f"The App Store version page ({version_state(page).replace('_', ' ').lower()}) now has build {build}; "
            f"its version number went {renamed}. Nothing was submitted for review.")


# --- Command line ------------------------------------------------------------------------------------------------


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("write-key")
    command.add_argument("--out", required=True)
    command = commands.add_parser("pick-xcode")
    command.add_argument("--version", default="")
    command = commands.add_parser("sign-archive")
    command.add_argument("archive")
    command = commands.add_parser("export-options")
    command.add_argument("--destination", choices=("export", "upload"), required=True)
    command.add_argument("--out", required=True)
    command = commands.add_parser("check-ipa")
    command.add_argument("ipa")
    command = commands.add_parser("attach-build")
    command.add_argument("--wait-minutes", type=int, default=60)
    args = parser.parse_args(argv)

    try:
        name, code = read_version()
        if args.command == "write-key":
            write_key(args.out)
        elif args.command == "pick-xcode":
            print(pick_xcode(glob.glob("/Applications/Xcode_*.app"), args.version.strip()))
        elif args.command == "sign-archive":
            sign_archive(args.archive, team_id())
        elif args.command == "export-options":
            with open(args.out, "wb") as file:
                plistlib.dump(export_options(args.destination, team_id()), file)
        elif args.command == "check-ipa":
            check_ipa(args.ipa, team_id(), name, code)
        elif args.command == "attach-build":
            summary = attach_build(AppStoreConnect(*credentials()), name, code, args.wait_minutes)
            print(summary)
            if os.environ.get("GITHUB_STEP_SUMMARY"):
                with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as file:
                    file.write(summary + "\n")
    except StepError as error:
        print(f"::error::{error}" if os.environ.get("GITHUB_ACTIONS") else f"Error: {error}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as error:
        print(f"Error: {' '.join(map(str, error.cmd[:2]))} exited with {error.returncode}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
