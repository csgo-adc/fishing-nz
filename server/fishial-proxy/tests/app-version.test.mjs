import assert from "node:assert/strict";
import test from "node:test";
import { loadWorker } from "./load-worker.mjs";

const worker = await loadWorker();
const env = { ANDROID_DOWNLOAD_URL: "https://play.google.com/store/apps/details?id=nz.fishingnz.app", IOS_DOWNLOAD_URL: "#" };

async function version(query) {
  return worker.fetch(new Request(`https://fishing.fishnz.space/v1/app/version${query}`), env, { waitUntil() {} });
}

test("the version endpoint lists Android releases newest first with the store link", async () => {
  const response = await version("?platform=android&lang=en");
  assert.equal(response.status, 200);
  assert.match(response.headers.get("cache-control"), /max-age=300/);
  const body = await response.json();
  assert.equal(body.platform, "android");
  assert.equal(body.store_url, env.ANDROID_DOWNLOAD_URL);
  assert.ok(body.minimum_build >= 1);
  assert.ok(body.releases.length >= 1);
  assert.deepEqual(body.latest, { version: body.releases[0].version, build: body.releases[0].build });
  for (let i = 1; i < body.releases.length; i++) assert.ok(body.releases[i - 1].build > body.releases[i].build);
  assert.ok(body.releases[0].notes.length > 0);
});

test("notes fall back to English and unknown store links are left out", async () => {
  const body = await (await version("?platform=ios&lang=fr-FR")).json();
  assert.equal(body.language, "en");
  assert.equal(body.store_url, null);
});

test("language tags map to the app's catalogues", async () => {
  for (const [tag, expected] of [["zh-CN", "zh-Hans"], ["zh-Hans-NZ", "zh-Hans"], ["zh-TW", "zh-Hant"], ["zh_HK", "zh-Hant"], ["ko-KR", "ko"], ["hi-IN", "hi"], ["", "en"]]) {
    const body = await (await version(`?platform=android&lang=${encodeURIComponent(tag)}`)).json();
    assert.equal(body.language, expected, tag);
  }
});

test("a missing or unknown platform is refused", async () => {
  assert.equal((await version("")).status, 400);
  assert.equal((await version("?platform=web")).status, 400);
});
