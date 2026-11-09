"""App Store upload helpers: API key secrets, Xcode choice, entitlements, API tokens and the version page."""

import base64
import json
import plistlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app_store_connect as asc
from app_store_connect import (
    RELEASE_ENTITLEMENTS, StepError, attach_build, choose_version, credentials, entitlement_problems, export_options,
    pick_xcode, private_key_text, signing_entitlements, team_id, write_key,
)

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
except ImportError:  # attach-build is the only part that needs it
    ec = None

KEY_ID = "ABC123DEF4"
ISSUER = "69a6de70-03db-47e3-e053-5b8c7c11a4d1"
TEAM = "8T7835869S"


def pem_key():
    key = ec.generate_private_key(ec.SECP256R1())
    text = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                             serialization.NoEncryption()).decode()
    return key, text


class KeyAndXcodeTest(unittest.TestCase):
    def test_private_key_accepts_text_or_base64(self):
        text = "-----BEGIN PRIVATE KEY-----\nMIGTAgEAMBMG\n-----END PRIVATE KEY-----"
        self.assertEqual(private_key_text("\n" + text + "\n\n"), text + "\n")
        self.assertEqual(private_key_text(base64.b64encode(text.encode()).decode()), text + "\n")
        for bad in ("", "not a key", base64.b64encode(b"hello").decode()):
            with self.assertRaises(StepError):
                private_key_text(bad)

    def test_credentials_name_missing_and_swapped_secrets(self):
        key = "-----BEGIN PRIVATE KEY-----\nMIGT\n-----END PRIVATE KEY-----"
        with self.assertRaisesRegex(StepError, "APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_PRIVATE_KEY"):
            credentials({"APP_STORE_CONNECT_KEY_ID": KEY_ID})
        with self.assertRaisesRegex(StepError, "Key ID"):
            credentials({"APP_STORE_CONNECT_KEY_ID": ISSUER, "APP_STORE_CONNECT_ISSUER_ID": KEY_ID,
                         "APP_STORE_CONNECT_PRIVATE_KEY": key})
        self.assertEqual(credentials({"APP_STORE_CONNECT_KEY_ID": f" {KEY_ID}\n", "APP_STORE_CONNECT_ISSUER_ID": ISSUER,
                                      "APP_STORE_CONNECT_PRIVATE_KEY": key})[:2], (KEY_ID, ISSUER))

    def test_write_key_uses_the_name_xcodebuild_expects(self):
        environ = {"APP_STORE_CONNECT_KEY_ID": KEY_ID, "APP_STORE_CONNECT_ISSUER_ID": ISSUER,
                   "APP_STORE_CONNECT_PRIVATE_KEY": "-----BEGIN PRIVATE KEY-----\nMIGT\n-----END PRIVATE KEY-----"}
        with tempfile.TemporaryDirectory() as folder:
            path = write_key(Path(folder, "keys", f"AuthKey_{KEY_ID}.p8"), environ)
            self.assertTrue(path.read_text().endswith("-----END PRIVATE KEY-----\n"))
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(StepError):
                write_key(Path(folder, "key.p8"), environ)

    def test_pick_xcode_prefers_the_newest_release(self):
        paths = ["/Applications/Xcode_16.4.app", "/Applications/Xcode_26.0.1.app", "/Applications/Xcode_26.1.app",
                 "/Applications/Xcode_27.0_beta_3.app", "/Applications/Xcode.app"]
        self.assertEqual(pick_xcode(paths), "/Applications/Xcode_26.1.app")
        self.assertEqual(pick_xcode(paths, "26.0.1"), "/Applications/Xcode_26.0.1.app")
        with self.assertRaisesRegex(StepError, "Xcode 26 or later"):
            pick_xcode(paths, "16.4")
        with self.assertRaisesRegex(StepError, "not installed"):
            pick_xcode(paths, "27.0")

    def test_project_has_one_team_and_app_store_export_options(self):
        self.assertEqual(team_id(), TEAM)
        options = export_options("upload", TEAM)
        self.assertEqual(options["method"], "app-store-connect")
        self.assertFalse(options["manageAppVersionAndBuildNumber"])


class EntitlementsTest(unittest.TestCase):
    def test_release_entitlements_are_written_out_in_full(self):
        with open(RELEASE_ENTITLEMENTS, "rb") as file:
            source = plistlib.load(file)
        signed = signing_entitlements(source, TEAM, "nz.fishingnz.catchcheck")
        self.assertEqual(signed["application-identifier"], f"{TEAM}.nz.fishingnz.catchcheck")
        self.assertEqual(signed["com.apple.developer.team-identifier"], TEAM)
        self.assertEqual(signed["keychain-access-groups"], [f"{TEAM}.nz.fishingnz.catchcheck"])
        self.assertEqual(signed["com.apple.developer.associated-domains"], ["applinks:fishing.fishnz.space"])
        self.assertEqual(signed["com.apple.developer.applesignin"], ["Default"])
        self.assertNotIn("$(", plistlib.dumps(signed).decode())

    def test_unknown_settings_and_lost_entitlements_are_reported(self):
        with self.assertRaises(StepError):
            signing_entitlements({"group": ["$(APP_GROUP)"]}, TEAM, "nz.fishingnz.catchcheck")
        expected = signing_entitlements({"keychain-access-groups": ["$(AppIdentifierPrefix)x"]}, TEAM, "x")
        self.assertEqual(entitlement_problems(expected, dict(expected, **{"beta-reports-active": True})), [])
        problems = entitlement_problems(expected, {"application-identifier": f"{TEAM}.x", "get-task-allow": True})
        self.assertEqual(len(problems), 3)


class FakeApi:
    """Answers like App Store Connect for one app, recording every request."""

    def __init__(self, versions, build_states):
        self.versions, self.build_states, self.calls, self.attached = versions, list(build_states), [], None

    def request(self, method, path, params=None, body=None):
        self.calls.append((method, path, body))
        if path == "/v1/apps":
            return {"data": [{"id": "app1", "attributes": {"bundleId": params["filter[bundleId]"], "name": "Fishdays"}}]}
        if path == "/v1/builds":
            state = self.build_states.pop(0) if len(self.build_states) > 1 else self.build_states[0]
            build = {"id": "build65", "attributes": {"version": params["filter[version]"], "processingState": state}}
            return {"data": [] if state == "NOT_FOUND" else [build]}
        if path == "/v1/apps/app1/appStoreVersions":
            return {"data": self.versions}
        if method == "PATCH" and path.endswith("/relationships/build"):
            self.attached = body["data"]["id"]
            return None
        if method == "GET" and path.endswith("/build"):
            return {"data": {"id": self.attached}}
        return {"data": {}}


def store_version(id, version, state, created):
    return {"id": id, "attributes": {"versionString": version, "appVersionState": state, "createdDate": created}}


class VersionPageTest(unittest.TestCase):
    def run_attach(self, versions, build_states=("NOT_FOUND", "PROCESSING", "VALID")):
        api, now, naps = FakeApi(versions, build_states), [0], []

        def sleep(seconds):
            naps.append(seconds)
            now[0] += seconds

        summary = attach_build(api, "1.0.64", 65, 60, sleep=sleep, clock=lambda: now[0])
        return api, summary, naps

    def test_choose_version(self):
        live = store_version("v1", "1.0.0", "READY_FOR_DISTRIBUTION", "2026-01-01")
        rejected = store_version("v2", "1.0.27", "DEVELOPER_REJECTED", "2026-10-01")
        review = store_version("v3", "1.1.0", "WAITING_FOR_REVIEW", "2026-11-01")
        self.assertEqual(choose_version([live, rejected]), ("update", rejected))
        self.assertEqual(choose_version([live, review]), ("wait", review))
        self.assertEqual(choose_version([live]), ("create", None))
        old = {"id": "v4", "attributes": {"versionString": "1.0.20", "appStoreState": "PREPARE_FOR_SUBMISSION"}}
        self.assertEqual(choose_version([old]), ("update", old))

    def test_waits_for_processing_then_renames_the_page_and_swaps_the_build(self):
        api, summary, naps = self.run_attach([store_version("v2", "1.0.27", "PREPARE_FOR_SUBMISSION", "2026-10-01")])
        self.assertEqual(naps, [30, 30])
        self.assertEqual(api.attached, "build65")
        rename = next(call for call in api.calls if call[:2] == ("PATCH", "/v1/appStoreVersions/v2"))
        self.assertEqual(rename[2]["data"]["attributes"], {"versionString": "1.0.64"})
        self.assertIn("from 1.0.27 to 1.0.64", summary)
        self.assertNotIn("POST", [call[0] for call in api.calls])  # never submits or adds a version here
        self.assertFalse(any("reviewSubmission" in call[1] for call in api.calls))

    def test_adds_the_next_version_once_the_last_is_live(self):
        api, summary, _ = self.run_attach([store_version("v1", "1.0.60", "READY_FOR_DISTRIBUTION", "2026-10-01")],
                                          ("VALID",))
        method, path, body = api.calls[-1]
        self.assertEqual((method, path), ("POST", "/v1/appStoreVersions"))
        self.assertEqual(body["data"]["attributes"], {"platform": "IOS", "versionString": "1.0.64"})
        self.assertEqual(body["data"]["relationships"]["build"]["data"]["id"], "build65")
        self.assertIn("Added App Store version 1.0.64", summary)

    def test_leaves_a_version_under_review_alone(self):
        api, summary, _ = self.run_attach([store_version("v3", "1.0.63", "IN_REVIEW", "2026-11-01")], ("VALID",))
        self.assertEqual({call[0] for call in api.calls}, {"GET"})
        self.assertIn("in review", summary)

    def test_failed_processing_and_timeouts_stop_the_job(self):
        with self.assertRaisesRegex(StepError, "could not process"):
            self.run_attach([], ("PROCESSING", "INVALID"))
        with self.assertRaisesRegex(StepError, "action 'attach'"):
            self.run_attach([], ("PROCESSING",))


class LocalizedApi(FakeApi):
    """A version being prepared with three App Store languages."""

    def __init__(self, versions):
        super().__init__(versions, ["VALID"])
        self.localizations = [
            {"id": "l1", "attributes": {"locale": "en-AU", "whatsNew": None}},
            {"id": "l2", "attributes": {"locale": "zh-Hans", "whatsNew": None}},
            {"id": "l3", "attributes": {"locale": "fr-FR", "whatsNew": None}},
        ]

    def request(self, method, path, params=None, body=None):
        if path.endswith("/appStoreVersionLocalizations"):
            self.calls.append((method, path, body))
            return {"data": self.localizations}
        if method == "PATCH" and path.startswith("/v1/appStoreVersionLocalizations/"):
            self.calls.append((method, path, body))
            localization = next(l for l in self.localizations if path.endswith(l["id"]))
            localization["attributes"]["whatsNew"] = body["data"]["attributes"]["whatsNew"]
            return {"data": localization}
        return super().request(method, path, params, body)


class WhatsNewTest(unittest.TestCase):
    def notes_file(self, folder, version="1.0.65", platforms=("ios",)):
        path = Path(folder, "app-versions.json")
        path.write_text(json.dumps({"releases": [{"version": version, "build": 66, "platforms": list(platforms),
                                                  "notes": {"en": ["Faster tides", "New widget"], "zh-Hans": ["潮汐更快"]}}]}))
        return path

    def test_release_notes_in_the_repository_fit_the_app_store(self):
        releases = json.loads(asc.APP_VERSIONS.read_text())["releases"]
        for release in releases:
            for locale in ("en-AU", "en-US", "zh-Hans", "zh-Hant", "ko", "hi"):
                self.assertIsNotNone(asc.whats_new_text(release["notes"], locale), (release["version"], locale))

    def test_each_language_gets_its_notes_or_english(self):
        notes = {"en": ["Faster tides", "New widget"], "zh-Hans": ["潮汐更快"]}
        self.assertEqual(asc.whats_new_text(notes, "en-AU"), "• Faster tides\n• New widget")
        self.assertEqual(asc.whats_new_text(notes, "zh-Hans"), "• 潮汐更快")
        self.assertEqual(asc.whats_new_text(notes, "ko"), "• Faster tides\n• New widget")
        with self.assertRaisesRegex(StepError, "4,000"):
            asc.whats_new_text({"en": ["x" * 4001]}, "en-AU")

    def test_fills_the_version_being_prepared_once(self):
        api = LocalizedApi([store_version("v2", "1.0.65", "PREPARE_FOR_SUBMISSION", "2026-11-09"),
                            store_version("v1", "1.0.28", "READY_FOR_DISTRIBUTION", "2026-10-11")])
        with tempfile.TemporaryDirectory() as folder:
            path = self.notes_file(folder)
            summary = asc.fill_whats_new(api, path=path)
            self.assertEqual(summary, "Filled What's New for 1.0.65 in en-AU, zh-Hans, fr-FR.")
            self.assertEqual(api.localizations[1]["attributes"]["whatsNew"], "• 潮汐更快")
            self.assertEqual(api.localizations[2]["attributes"]["whatsNew"], "• Faster tides\n• New widget")
            self.assertIn("already up to date", asc.fill_whats_new(api, path=path))
        self.assertFalse(any(call[0] == "POST" for call in api.calls))

    def test_missing_notes_or_page_stop_or_skip(self):
        api = LocalizedApi([store_version("v2", "1.0.65", "PREPARE_FOR_SUBMISSION", "2026-11-09")])
        with tempfile.TemporaryDirectory() as folder:
            android_only = self.notes_file(folder, platforms=("android",))
            with self.assertRaisesRegex(StepError, "no iOS release notes for 1.0.65"):
                asc.fill_whats_new(api, path=android_only)
            self.assertIn("left as it is", asc.fill_whats_new(api, only_if_listed=True, path=android_only))
            live = LocalizedApi([store_version("v1", "1.0.65", "READY_FOR_DISTRIBUTION", "2026-11-09")])
            with self.assertRaisesRegex(StepError, "No App Store version is being prepared"):
                asc.fill_whats_new(live, path=self.notes_file(folder))
        self.assertFalse(any(call[0] == "PATCH" for call in api.calls + live.calls))

    def test_status_lists_versions_and_whats_new(self):
        api = LocalizedApi([store_version("v1", "1.0.28", "READY_FOR_DISTRIBUTION", "2026-10-11"),
                            store_version("v2", "1.0.65", "PREPARE_FOR_SUBMISSION", "2026-11-09")])
        api.attached = "build66"
        api.localizations[0]["attributes"]["whatsNew"] = "• Faster tides"
        original = api.request

        def request(method, path, params=None, body=None):
            if path.endswith("/build"):
                return {"data": {"id": "build66", "attributes": {"version": "66"}}}
            return original(method, path, params, body)

        api.request = request
        report = asc.status(api)
        self.assertLess(report.index("1.0.65: prepare for submission"), report.index("1.0.28: ready for distribution"))
        self.assertIn("Being prepared: 1.0.65 with build 66", report)
        self.assertIn("en-AU: '• Faster tides'", report)
        self.assertIn("zh-Hans: (empty)", report)


@unittest.skipIf(ec is None, "cryptography is not installed")
class TokenTest(unittest.TestCase):
    def test_token_is_an_es256_jwt_apple_accepts(self):
        key, text = pem_key()
        token = asc.AppStoreConnect(KEY_ID, ISSUER, text, clock=lambda: 1_800_000_000).token()
        header, claims, signature = token.split(".")
        decode = lambda part: base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))
        self.assertEqual(json.loads(decode(header)), {"alg": "ES256", "kid": KEY_ID, "typ": "JWT"})
        self.assertEqual(json.loads(decode(claims)), {"iss": ISSUER, "iat": 1_800_000_000,
                                                      "exp": 1_800_000_900, "aud": "appstoreconnect-v1"})
        raw = decode(signature)
        self.assertEqual(len(raw), 64)
        der = encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
        key.public_key().verify(der, f"{header}.{claims}".encode(), ec.ECDSA(hashes.SHA256()))

    def test_retries_network_errors_and_busy_answers_but_not_a_post(self):
        import urllib.error

        naps = []
        api = asc.AppStoreConnect(KEY_ID, ISSUER, pem_key()[1], sleep=naps.append)
        answers = [urllib.error.URLError("reset"), (503, None), (200, {"data": []})]

        def send(method, url, body):
            answer = answers.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return answer

        api.send = send
        self.assertEqual(api.request("GET", "/v1/apps", {"filter[bundleId]": "x"}), {"data": []})
        self.assertEqual(naps, [10, 20])
        answers[:] = [(409, {"errors": [{"title": "Conflict", "detail": "The version already exists"}]})]
        with self.assertRaisesRegex(StepError, "HTTP 409: Conflict — The version already exists"):
            api.request("POST", "/v1/appStoreVersions", body={})
        answers[:] = [urllib.error.URLError("reset")]
        with self.assertRaisesRegex(StepError, "could not be reached"):
            api.request("POST", "/v1/appStoreVersions", body={})
        self.assertEqual(naps, [10, 20])


if __name__ == "__main__":
    unittest.main()
