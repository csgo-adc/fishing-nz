import { build } from "esbuild";
import { fileURLToPath } from "node:url";

export async function bundleWorker() {
  const result = await build({
    entryPoints: [fileURLToPath(new URL("../src/index.ts", import.meta.url))],
    bundle: true, write: false, format: "esm", target: "es2022", platform: "browser",
  });
  return result.outputFiles[0].text;
}

export async function loadWorker() {
  return (await import(`data:text/javascript;base64,${Buffer.from(await bundleWorker()).toString("base64")}`)).default;
}
