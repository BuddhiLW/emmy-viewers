// Browser test of a notebook exported by emmy.viewer.export/export-notebook!.
//
// Serves the export folder over HTTP (the page fetches its content from
// index.edn, so file:// does not work), opens it in headless Chromium and
// checks that the page raises no errors, loads the viewer bundle and mounts
// at least one Mafs <svg> (or, with EXPORT_E2E_KIND=mathbox, one MathBox
// <canvas>).
//
// Export first, then run:
//
//   clojure -X:nextjournal/clerk emmy.viewer.export/export-notebook! \
//     :notebook '"dev/examples/mafs.clj"' :out-dir '"target/export-mafs"'
//   node dev/e2e/export_render.mjs
//
// MathBox: dev/e2e/mathbox_ticks.clj is the smallest notebook with tick labels
// (MathBox calls the tick formatter with 8 arguments, see CHANGELOG 0.5.1-fork.1):
//
//   clojure -X:nextjournal/clerk:cljs emmy.viewer.export/export-notebook! \
//     :notebook '"dev/e2e/mathbox_ticks.clj"' :out-dir '"target/export-mathbox"'
//   EXPORT_E2E_BUILD=target/export-mathbox EXPORT_E2E_KIND=mathbox \
//     node dev/e2e/export_render.mjs
//
// EXPORT_E2E_BUILD sets the folder (default target/export-mafs);
// EXPORT_E2E_KIND is mafs (default) or mathbox; CHROMIUM the browser binary. Unlike raster_render.mjs, remote scripts are allowed:
// by default the export loads Emmy-Viewers' published bundle from Clerk's CDN.

import { chromium } from "playwright-core";
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { existsSync, readdirSync } from "node:fs";
import { join, extname, resolve } from "node:path";
import { homedir } from "node:os";

const ROOT = resolve(process.env.EXPORT_E2E_BUILD ?? "target/export-mafs");
const KIND = process.env.EXPORT_E2E_KIND ?? "mafs";
const MOUNT = { mafs: [".MafsView svg", "Mafs <svg>"],
                mathbox: ["canvas", "MathBox <canvas>"] }[KIND];
if (!MOUNT) throw new Error(`unknown EXPORT_E2E_KIND ${KIND}`);

function chromiumPath() {
  if (process.env.CHROMIUM) return process.env.CHROMIUM;
  const base = join(homedir(), ".cache/ms-playwright");
  for (const dir of readdirSync(base).filter((d) => /^chromium-\d+$/.test(d)).sort().reverse()) {
    for (const sub of ["chrome-linux64", "chrome-linux"]) {
      const p = join(base, dir, sub, "chrome");
      if (existsSync(p)) return p;
    }
  }
  throw new Error("No Chromium found; set CHROMIUM");
}

const TYPES = {
  ".html": "text/html", ".js": "text/javascript", ".css": "text/css",
  ".json": "application/json", ".edn": "application/edn", ".svg": "image/svg+xml",
  ".png": "image/png", ".woff2": "font/woff2", ".woff": "font/woff",
};

function serve(root) {
  const server = createServer(async (req, res) => {
    try {
      let p = join(root, decodeURIComponent(new URL(req.url, "http://x").pathname));
      if (!p.startsWith(root)) throw new Error("outside root");
      if ((await stat(p)).isDirectory()) p = join(p, "index.html");
      const body = await readFile(p);
      res.writeHead(200, { "content-type": TYPES[extname(p)] ?? "application/octet-stream" });
      res.end(body);
    } catch {
      res.writeHead(404);
      res.end();
    }
  });
  return new Promise((ok) => server.listen(0, "127.0.0.1", () => ok(server)));
}

const failures = [];
const check = (ok, msg) => { console.log(`${ok ? "ok  " : "FAIL"} ${msg}`); if (!ok) failures.push(msg); };

async function main() {
  if (!existsSync(join(ROOT, "index.html"))) throw new Error(`no export at ${ROOT}`);
  const server = await serve(ROOT);
  const origin = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({
    executablePath: chromiumPath(),
    args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
    const errors = [], consoleErrors = [], scripts = [], failed = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
    page.on("request", (r) => { if (r.resourceType() === "script") scripts.push(r.url()); });
    page.on("requestfailed", (r) => failed.push(`${r.resourceType()} ${r.url()}`));
    page.on("response", (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.url()}`); });

    await page.goto(`${origin}/`, { waitUntil: "load" });
    await page.waitForFunction((sel) => document.querySelectorAll(sel).length > 0,
                               MOUNT[0], { timeout: Number(process.env.EXPORT_E2E_TIMEOUT ?? 60000) }).catch(() => {});
    await page.waitForTimeout(2000);
    await page.screenshot({ path: join(ROOT, "screenshot.png"), fullPage: true });

    // The viewer bundle: Clerk's _data/<hash>.js when compiled locally, or
    // the CDN URL of emmy.clerk/custom-js.
    const viewer = scripts.find((u) => /\/_data\/[^/]+\.js$|shadow-cljs\/main\.js$/.test(u));
    check(!!viewer, `viewer bundle requested (${viewer ?? "none"})`);
    const heading = await page.evaluate(() => document.querySelector("h1, h2")?.textContent ?? null);
    check(heading != null && heading.length > 0, `notebook prose rendered (first heading: ${JSON.stringify(heading)})`);
    const mounted = await page.evaluate((sel) => document.querySelectorAll(sel).length, MOUNT[0]);
    check(mounted > 0, `${mounted} ${MOUNT[1]} mounted`);
    const distinct = [...new Set(errors)];
    console.log(`     page errors: ${errors.length} (${distinct.length} distinct)`);
    check(errors.length === 0, `no page errors${errors.length ? ": " + distinct.slice(0, 5).join(" | ") : ""}`);
    if (consoleErrors.length) console.log(`     console errors (not page errors): ${consoleErrors.slice(0, 5).join(" | ")}`);
    if (failed.length) console.log(`     failed requests: ${failed.slice(0, 5).join(" | ")}`);
  } finally {
    await browser.close();
    server.close();
  }
  console.log(failures.length ? `\n${failures.length} check(s) failed` : "\nall checks passed");
  process.exit(failures.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
