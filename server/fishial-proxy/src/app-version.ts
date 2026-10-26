import { corsHeaders } from "./http";
import appVersions from "../app-versions.json";

/**
 * GET /v1/app/version?platform=android|ios&lang=en — the newest release for one platform, the oldest build that may
 * still run, and short "what's new" notes in the app's language. The apps use it for the update pop-up
 * (docs/in-app-updates.md). Edit app-versions.json and deploy to announce a release or require an update.
 */

type Platform = "android" | "ios";
type Release = { version: string; build: number; platforms: string[]; notes: Record<string, string[]> };
type VersionConfig = { minimum_build: Record<Platform, number>; releases: Release[] };

export type VersionEnv = { ANDROID_DOWNLOAD_URL?: string; IOS_DOWNLOAD_URL?: string };

const MAX_RELEASES = 5;
const LANGUAGES = ["en", "zh-Hans", "zh-Hant", "ko", "hi"];

/** The app language a client asked for, falling back to English. Accepts tags such as zh-CN, zh-TW and ko-KR. */
export function releaseLanguage(value: string | null): string {
  const tag = (value ?? "").trim().replace(/_/g, "-");
  const lower = tag.toLowerCase();
  if (LANGUAGES.includes(tag)) return tag;
  if (lower.startsWith("zh")) {
    return /hant|-tw|-hk|-mo/.test(lower) ? "zh-Hant" : "zh-Hans";
  }
  const base = lower.split("-")[0];
  return LANGUAGES.includes(base) ? base : "en";
}

function storeUrl(value: string | undefined): string | null {
  const url = value?.trim() ?? "";
  return /^https:\/\/[^\s"'<>]+$/.test(url) ? url : null;
}

export function versionInfo(platform: Platform, language: string, env: VersionEnv, config: VersionConfig = appVersions as VersionConfig) {
  const releases = config.releases
    .filter((release) => release.platforms.includes(platform) && Number.isSafeInteger(release.build))
    .sort((a, b) => b.build - a.build)
    .slice(0, MAX_RELEASES)
    .map((release) => ({
      version: release.version,
      build: release.build,
      notes: release.notes[language] ?? release.notes.en ?? [],
    }));
  const latest = releases[0] ?? null;
  return {
    platform,
    language,
    latest: latest ? { version: latest.version, build: latest.build } : null,
    minimum_build: Math.max(1, Math.trunc(config.minimum_build[platform] ?? 1)),
    store_url: storeUrl(platform === "android" ? env.ANDROID_DOWNLOAD_URL : env.IOS_DOWNLOAD_URL),
    releases,
  };
}

export function handleAppVersionRoute(request: Request, env: VersionEnv): Response | null {
  const url = new URL(request.url);
  if (url.pathname !== "/v1/app/version" || request.method !== "GET") return null;
  const platform = url.searchParams.get("platform");
  if (platform !== "android" && platform !== "ios") {
    return new Response(JSON.stringify({ error: "platform must be android or ios" }), {
      status: 400, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...corsHeaders() },
    });
  }
  const body = versionInfo(platform, releaseLanguage(url.searchParams.get("lang")), env);
  return new Response(JSON.stringify(body), {
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=300", ...corsHeaders() },
  });
}
