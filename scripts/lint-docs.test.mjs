import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { micromark } from 'micromark';
import { math, mathHtml } from 'micromark-extension-math';
import { lintDocs, markdownFiles } from './lint-docs.mjs';

const ruleConfig = await readFile(new URL('../.markdownlint.json', import.meta.url), 'utf8');
const script = new URL('./lint-docs.mjs', import.meta.url).href;
const goodMarkdown = '# A heading\n\nA paragraph.\n';
async function fixture(t, entries) {
  const root = await mkdtemp(path.join(tmpdir(), 'rx8-doc-lint-'));
  t.after(async () => {
    // Delete only the exact temporary fixture created above, never a computed repository path.
    assert.equal(path.dirname(path.resolve(root)), path.resolve(tmpdir()));
    assert.ok(path.basename(root).startsWith('rx8-doc-lint-'));
    await rm(root, { recursive: true, force: true });
  });
  for (const [relative, contents] of Object.entries({ '.markdownlint.json': ruleConfig, ...entries })) {
    const file = path.join(root, relative);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, contents, 'utf8');
  }
  return root;
}
function cli(root) {
  return spawnSync(process.execPath, ['--input-type=module', '-e',
    `import {runCli} from ${JSON.stringify(script)}; process.exitCode = await runCli(${JSON.stringify(root)});`],
  { encoding: 'utf8', timeout: 10000 });
}

test('discovers root, nested, API and hidden Markdown while excluding vendor/generated files', async (t) => {
  const included = ['README.md', 'docs/deep/guide.md', 'api/README.md', '.github/PULL_REQUEST_TEMPLATE.md',
    '.hidden/notes.md', 'api/distinct/keep.md', 'dist/keep.md'];
  const excluded = ['node_modules/vendor/bad.md', 'api/node_modules/vendor/bad.md', 'api/dist/bad.md', '.git/bad.md'];
  const root = await fixture(t, {
    ...Object.fromEntries(included.map((file) => [file, goodMarkdown])),
    ...Object.fromEntries(excluded.map((file) => [file, 'Not a heading\n'])),
    'docs/plain.txt': 'Not a Markdown file',
  });
  assert.deepEqual(await markdownFiles(root), [...included].sort());
  const result = await lintDocs(root);
  assert.deepEqual(result.files, [...included].sort());
  assert.deepEqual(result.issues, []);
});

test('reports real rule violations in a hidden GitHub template with file and line information', async (t) => {
  const root = await fixture(t, { '.github/PULL_REQUEST_TEMPLATE.md': '# Heading\n### Skipped level\n' });
  const { issues } = await lintDocs(root);
  assert.ok(issues.some((issue) => issue.file === '.github/PULL_REQUEST_TEMPLATE.md' && issue.lineNumber === 2 && issue.ruleNames.includes('MD001')));
  assert.ok(issues.some((issue) => issue.ruleNames.includes('MD022')));
});

test('preserves disabled line-length/table-alignment rules and normal enabled rules', async (t) => {
  const root = await fixture(t, { 'README.md': `# Heading\n\n${'Long text '.repeat(30).trimEnd()}\n\n| Short | Longer name |\n| --- | --- |\n| A | B |\n` });
  assert.deepEqual((await lintDocs(root)).issues, []);
  await writeFile(path.join(root, 'README.md'), 'No heading\n', 'utf8');
  assert.ok((await lintDocs(root)).issues.some((issue) => issue.ruleNames.includes('MD041')));
});

test('preserves inline rule configuration and math syntax tokenization', async (t) => {
  const root = await fixture(t, { 'README.md': '<!-- markdownlint-disable MD041 -->\nAn introduction.\n\n$x_1 = y_2$\n\n$$\nx_1^2 + y_2^2\n$$\n' });
  assert.deepEqual((await lintDocs(root)).issues, []);
});

test('patched KaTeX remains compatible with the math extension and default untrusted rendering', () => {
  const html = micromark('$x^2$\n\n$$\nx_1 + y_2\n$$\n', { extensions: [math()], htmlExtensions: [mathHtml()] });
  assert.match(html, /class="katex/);
  assert.match(html, /katex-display/);
  const untrusted = micromark('$\\href{javascript:alert(1)}{link}$', { extensions: [math()], htmlExtensions: [mathHtml()] });
  assert.doesNotMatch(untrusted, /href="javascript:/);
});

test('CLI exits successfully only for valid documents and propagates lint failure', async (t) => {
  const root = await fixture(t, { 'README.md': goodMarkdown });
  const good = cli(root);
  assert.equal(good.status, 0, good.stderr);
  assert.match(good.stdout, /1 files, 0 issues/);
  await writeFile(path.join(root, 'README.md'), '# Heading\n### Skipped level\n', 'utf8');
  const bad = cli(root);
  assert.equal(bad.status, 1);
  assert.match(bad.stderr, /README\.md:2.*MD001/);
});

test('missing/malformed configuration and zero-document coverage cannot silently pass', async (t) => {
  const empty = await fixture(t, {});
  await assert.rejects(lintDocs(empty), /No Markdown files/);
  assert.equal(cli(empty).status, 1);
  const broken = await fixture(t, { '.markdownlint.json': '{broken', 'README.md': goodMarkdown });
  await assert.rejects(lintDocs(broken), SyntaxError);
  assert.equal(cli(broken).status, 1);
  await rm(path.join(broken, '.markdownlint.json'));
  await assert.rejects(lintDocs(broken), { code: 'ENOENT' });
  assert.equal(cli(broken).status, 1);
});

test('the executable rejects unsupported options rather than claiming to apply fixes', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(script), '--fix'], { encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /without CLI options/);
});
