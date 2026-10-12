import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Only explicit numeric scale declarations are accepted; never evaluate Kotlin text.
const source = readFileSync(new URL('../../core/telemetry/src/main/kotlin/com/gonibix/rx8/telemetry/obd/ObdField.kt', import.meta.url), 'utf8');
const scales = new Map([...source.matchAll(/^    ([A-Z0-9_]+)\("([^"]+)", (-?[\d.]+), (-?[\d.]+), \d+\)[,;]/gm)]
  .map((m) => [m[1], { unit: ({ '°C': 'Cel', 'N·m': 'N.m', lambda: '1', count: '1' })[m[2]] ?? m[2], minimum: Number(m[3]), maximum: Number(m[4]) }]));
const catalog = Object.fromEntries([...source.matchAll(/^    ([A-Z0-9_]+)\(0x([A-F0-9]{2}), "[^"]+", ObdScale\.([A-Z0-9_]+)/gm)].map((m) => {
  const scale = scales.get(m[3]);
  if (!scale) throw new Error('Unknown catalog scale');
  return [m[1].toLowerCase(), { pid: m[2], ...scale }];
}));
if (Object.keys(catalog).length !== 120) throw new Error('Review changed metric count before regenerating');
const encoded = JSON.stringify(catalog, null, 2) + '\n';
const destination = new URL('../src/catalog.json', import.meta.url);
if (process.argv.includes('--check')) {
  if (readFileSync(destination, 'utf8') !== encoded) throw new Error('API catalog differs from Android. Run npm run catalog and review.');
} else writeFileSync(fileURLToPath(destination), encoded);
