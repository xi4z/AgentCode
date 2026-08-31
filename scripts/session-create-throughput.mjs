#!/usr/bin/env node
// HTTP session-creation throughput test (no Agent run).
// Usage: node scripts/session-create-throughput.mjs --base http://localhost:18080 --samples 100 --concurrency 20
const args = process.argv.slice(2);
function argValue(name, fallback) {
  const idx = args.indexOf('--' + name);
  return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
}
const base = argValue('base', 'http://localhost:18080');
const samples = Number(argValue('samples', 100));
const concurrency = Number(argValue('concurrency', 20));

function pct(arr, p) {
  if (!arr.length) return null;
  const sorted = [...arr].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1)];
}

async function createOnce(i) {
  const t0 = Date.now();
  const res = await fetch(`${base}/api/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ goal: `创建吞吐测试-${i}-${Date.now()}`, workspace: '/tmp' })
  });
  const body = await res.json();
  return { ok: res.ok && body.runId, latencyMs: Date.now() - t0, runId: body.runId };
}

async function main() {
  const start = Date.now();
  const results = [];
  for (let offset = 0; offset < samples; offset += concurrency) {
    const batch = [];
    for (let j = 0; j < concurrency && offset + j < samples; j++) {
      batch.push(createOnce(offset + j));
    }
    results.push(...await Promise.all(batch));
  }
  const totalMs = Date.now() - start;
  const ok = results.filter(r => r.ok).length;
  const latencies = results.filter(r => r.ok).map(r => r.latencyMs);
  console.log(JSON.stringify({
    endpoint: `${base}/api/sessions`,
    samples,
    concurrency,
    success: ok,
    successRate: Number((ok / samples * 100).toFixed(2)),
    totalMs,
    qps: Number((ok / (totalMs / 1000)).toFixed(2)),
    latency: {
      p50Ms: pct(latencies, 50),
      p95Ms: pct(latencies, 95),
      maxMs: latencies.length ? Math.max(...latencies) : null
    }
  }, null, 2));
}
main().catch(e => { console.error(e); process.exit(1); });