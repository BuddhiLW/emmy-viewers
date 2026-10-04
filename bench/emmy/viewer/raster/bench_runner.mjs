// Node side of emmy.viewer.raster.bench: times each case's :js function, the
// :raster glue function called per point, and the same glue's batch call over
// all points, on the same inputs.
//
//   node bench_runner.mjs <config.json>
//
// Prints one JSON object: {node, cases: [{id, variants: {js, raster, batch},
// maxAbsDiff}]}, each variant {median, p10, p90} in ns per point.

import { readFileSync } from "node:fs";

const config = JSON.parse(readFileSync(process.argv[2], "utf8"));
const { trials, minTrialMs } = config;

let sink = 0; // consumed at the end, so no call is dead code

const sum = (v) => {
  if (typeof v === "number") return v;
  let s = 0;
  for (const x of v) s += sum(x);
  return s;
};

const flat = (v, out = []) => {
  if (typeof v === "number") out.push(v);
  else for (const x of v) flat(x, out);
  return out;
};

// Runs `pass` (one sweep over the inputs) repeatedly; returns ns per call.
function measure(pass, callsPerPass) {
  for (let i = 0; i < 5; i++) pass(); // warm up the JIT
  let reps = 1;
  for (;;) {
    const t0 = process.hrtime.bigint();
    for (let r = 0; r < reps; r++) pass();
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    if (ms >= minTrialMs) break;
    reps *= 2;
  }
  const samples = [];
  for (let t = 0; t < trials; t++) {
    const t0 = process.hrtime.bigint();
    for (let r = 0; r < reps; r++) pass();
    samples.push(Number(process.hrtime.bigint() - t0) / (reps * callsPerPass));
  }
  samples.sort((a, b) => a - b);
  const q = (p) => samples[Math.min(samples.length - 1, Math.floor(p * samples.length))];
  return { median: q(0.5), p10: q(0.1), p90: q(0.9) };
}

function runCase(c) {
  const fb = new Function(...c.jsArgs, c.jsBody);
  const f = new Function("fb", c.glue)(fb);
  if (!f.wasm()) throw new Error(`${c.id}: kernels did not load synchronously`);
  const inputs = c.inputs;
  const P = c.params;
  const n = inputs.length;
  const m = c.nOut;
  const out = new Array(m).fill(0);
  // Single-argument points as plain numbers, so neither side pays for spread.
  const x1 = inputs.map((x) => x[0]);
  const X = Float64Array.from(inputs.flat());
  const O = new Float64Array(n * m);
  let js, raster, results;

  if (c.convention === "native" && inputs[0].length === 1) {
    js = () => { for (let i = 0; i < n; i++) sink += sum(fb(x1[i])); };
    raster = () => { for (let i = 0; i < n; i++) sink += sum(f(x1[i])); };
    results = (g) => x1.map((x) => flat(g(x)));
  } else if (c.convention === "native") {
    js = () => { for (let i = 0; i < n; i++) sink += sum(fb(...inputs[i])); };
    raster = () => { for (let i = 0; i < n; i++) sink += sum(f(...inputs[i])); };
    results = (g) => inputs.map((x) => flat(g(...x)));
  } else if (c.convention === "structure") {
    js = () => { for (let i = 0; i < n; i++) sink += sum(fb(inputs[i], P)); };
    raster = () => { for (let i = 0; i < n; i++) sink += sum(f(inputs[i], P)); };
    results = (g) => inputs.map((x) => flat(g(x, P)));
  } else {
    js = () => { for (let i = 0; i < n; i++) { fb(inputs[i], out, P); sink += out[0]; } };
    raster = () => { for (let i = 0; i < n; i++) { f(inputs[i], out, P); sink += out[0]; } };
    results = (g) => inputs.map((x) => { const o = new Array(m).fill(0); g(x, o, P); return o; });
  }
  // Every point in one call: what a viewer sampling a curve or grid can use.
  const batch = () => { f.batch(X, n, P, O); sink += O[0]; };

  let maxAbsDiff = 0;
  const a = results(fb), b = results(f);
  for (let i = 0; i < n; i++)
    for (let j = 0; j < a[i].length; j++)
      maxAbsDiff = Math.max(maxAbsDiff, Math.abs(a[i][j] - b[i][j]));
  f.batch(X, n, P, O);
  for (let i = 0; i < n; i++)
    for (let j = 0; j < m; j++)
      maxAbsDiff = Math.max(maxAbsDiff, Math.abs(a[i][j] - O[i * m + j]));

  return {
    id: c.id,
    variants: { js: measure(js, n), raster: measure(raster, n), batch: measure(batch, n) },
    maxAbsDiff,
  };
}

const cases = config.cases.map(runCase);
console.log(JSON.stringify({ node: process.version, cases, sink: Number.isFinite(sink) }));
