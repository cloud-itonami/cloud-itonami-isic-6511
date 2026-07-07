// Node-hosted (no JVM) execution of underwriting_decision.wasm through
// kotoba-lang/wasm-webcomponent's actor-host.js port of the actor:host ABI.
//
// Why this exists: kototama.tender (JVM/Chicory) is the more mature host,
// but the murakumo fleet's Mac-mini nodes have no JVM installed (only the
// pinned Rust kotoba/kotoba-server binaries + a native XMRig miner) --
// verified directly, 2026-07-07: `java -version` on asher/naphtali/judah/
// zebulun/issachar all report "Unable to locate a Java Runtime". Node.js
// has no such gap here (a much smaller, more common install than a JDK),
// and kotoba-lang/wasm-webcomponent already ships a real, tested,
// dependency-free actor:host port that runs under plain `node` (see its
// own test/verify-actor-host.mjs) -- this script is that same pattern,
// pointed at this repo's own compiled PoC module instead of that repo's
// fixtures, plus a Node-only synchronous `llm-infer` backend (a browser
// tab cannot make a blocking network call; a `child_process` in Node has
// no such constraint -- see actor-host.js's `llm-infer` comment).
//
// Assumes the fixed sibling-checkout layout this monorepo's west manifest
// always uses (orgs/<org>/<repo>) -- not a portable npm dependency, since
// wasm-webcomponent is deliberately a zero-build-step, no-package.json
// library (see its own README).
//
// Run: node wasm/verify_node.mjs <scenario>
//   scenarios: auto-ok | hold-no-spec-basis | hold-sanctions |
//              hold-incomplete-docs | escalate-actuation |
//              escalate-low-confidence

import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import {
  hostCaps,
  actorHostImports,
  inMemoryStore,
} from '../../../kotoba-lang/wasm-webcomponent/src/actor-host.js';

const here = path.dirname(fileURLToPath(import.meta.url));

// byte 0: has-spec-basis? | byte 1: sanctions-hit? | byte 2: docs-complete?
// byte 3: confidence-x100 | byte 4: is-actuation?  -- see underwriting_decision.kotoba
const SCENARIOS = {
  'auto-ok':                 [1, 0, 1, 90, 0],
  'hold-no-spec-basis':      [0, 0, 1, 90, 0],
  'hold-sanctions':          [1, 1, 1, 90, 0],
  'hold-incomplete-docs':    [1, 0, 0, 90, 0],
  'escalate-actuation':      [1, 0, 1, 90, 1],
  'escalate-low-confidence': [1, 0, 1, 40, 0],
};

const DECISION_NAMES = { 0: 'ok (auto)', 1: 'escalate', 2: 'hold' };

// Same env-var chain cloud_itonami.runtime/model-config and
// kototama.tender's resolve-llm-api-key use -- fails closed (null) when
// none is set, exactly like the JVM side, rather than throwing.
const API_KEY_ENV_VARS = ['ITO_MODEL_API_KEY', 'OPENAI_API_KEY', 'OPENCLAW_API_KEY', 'HERMES_API_KEY', 'ANTHROPIC_API_KEY'];

function resolveApiKey() {
  for (const name of API_KEY_ENV_VARS) {
    const v = process.env[name];
    if (v && v.trim()) return v.trim();
  }
  return null;
}

// A genuinely synchronous llm-infer backend: `execFileSync` blocks this
// Node process until curl exits, unlike `fetch` -- exactly the property a
// WebAssembly host-import function call requires (see actor-host.js).
function llmInferViaCurl(prompt) {
  const apiKey = resolveApiKey();
  if (!apiKey) return null;
  try {
    const body = JSON.stringify({
      model: 'claude-opus-4-8',
      max_tokens: 256,
      messages: [{ role: 'user', content: prompt }],
    });
    const out = execFileSync('curl', [
      '-sS', '--max-time', '30',
      'https://api.anthropic.com/v1/messages',
      '-H', 'content-type: application/json',
      '-H', `x-api-key: ${apiKey}`,
      '-H', 'anthropic-version: 2023-06-01',
      '-d', body,
    ], { encoding: 'utf-8' });
    const parsed = JSON.parse(out);
    const text = (parsed.content || [])
      .filter((c) => c.type === 'text')
      .map((c) => c.text)
      .join('');
    return text && text.trim() ? text : null;
  } catch {
    return null;
  }
}

async function run(scenario) {
  const inputBytes = SCENARIOS[scenario];
  if (!inputBytes) {
    console.error(`unknown scenario ${JSON.stringify(scenario)}; known: ${Object.keys(SCENARIOS).join(', ')}`);
    process.exit(64);
  }

  const wasmBytes = await readFile(path.join(here, 'underwriting_decision.wasm'));
  const store = inMemoryStore();
  const memoryBox = {};
  const caps = hostCaps({
    grants: ['log-write', 'llm-infer'],
    limits: { allowWriteImports: true, maxLlmInfers: 1 },
  });
  const importObject = {
    kotoba: actorHostImports(['log-write', 'llm-infer'], caps, memoryBox, {
      store,
      llmInfer: llmInferViaCurl,
    }),
  };

  const { instance } = await WebAssembly.instantiate(wasmBytes, importObject);
  memoryBox.memory = instance.exports.memory;

  new Uint8Array(memoryBox.memory.buffer, 0, 5).set(inputBytes);
  const raw = instance.exports.main();
  const code = typeof raw === 'bigint' ? Number(raw) : raw;
  const logged = new TextDecoder('utf-8').decode(store.read());

  console.log(JSON.stringify({
    scenario,
    input: { hasSpecBasis: inputBytes[0], sanctionsHit: inputBytes[1], docsComplete: inputBytes[2], confidenceX100: inputBytes[3], isActuation: inputBytes[4] },
    decisionCode: code,
    decisionName: DECISION_NAMES[code] ?? `unknown(${code})`,
    log: logged,
    apiKeyConfigured: resolveApiKey() !== null,
  }, null, 2));
}

const scenario = process.argv[2];
if (!scenario) {
  console.error(`usage: node ${path.basename(import.meta.url)} <scenario>\nscenarios: ${Object.keys(SCENARIOS).join(', ')}`);
  process.exit(64);
}
await run(scenario);
