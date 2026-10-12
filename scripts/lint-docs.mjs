import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { lint } from 'markdownlint/promise';

const repositoryRoot = fileURLToPath(new URL('../', import.meta.url));

/** Same repository coverage as the previous recursive Markdown CLI glob, including hidden templates. */
export async function markdownFiles(root) {
  const files = [];
  async function walk(directory, relative = '') {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const name = relative ? `${relative}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        if (entry.name !== 'node_modules' && entry.name !== '.git' && name !== 'api/dist') {
          await walk(path.join(directory, entry.name), name);
        }
      } else if (entry.isFile() && entry.name.endsWith('.md')) {
        files.push(name);
      }
    }
  }
  await walk(root);
  return files.sort();
}

export async function lintDocs(root = repositoryRoot) {
  const config = JSON.parse(await readFile(path.join(root, '.markdownlint.json'), 'utf8'));
  if (!config || Array.isArray(config) || typeof config !== 'object') {
    throw new Error('.markdownlint.json must contain a rule configuration object.');
  }
  const files = await markdownFiles(root);
  if (files.length === 0) throw new Error('No Markdown files found; documentation coverage cannot be verified.');
  const absoluteFiles = files.map((file) => path.resolve(root, file));
  const results = await lint({ files: absoluteFiles, config });
  const issues = files.flatMap((file, index) => results[absoluteFiles[index]].map((issue) => ({ file, ...issue })));
  return { files, issues };
}

export async function runCli(root = repositoryRoot) {
  try {
    const { files, issues } = await lintDocs(root);
    for (const issue of issues) {
      const column = issue.errorRange ? `:${issue.errorRange[0]}` : '';
      const detail = issue.errorDetail ? ` [${issue.errorDetail}]` : '';
      console.error(`${issue.file}:${issue.lineNumber}${column} ${issue.ruleNames[0]} ${issue.ruleDescription}${detail}`);
    }
    console.log(`Documentation: ${files.length} files, ${issues.length} issues.`);
    return issues.length ? 1 : 0;
  } catch (error) {
    console.error(`Documentation lint failed: ${error.message}`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  if (process.argv.length !== 2) {
    console.error('Use npm run lint:docs without CLI options; rules live in .markdownlint.json.');
    process.exitCode = 1;
  } else {
    process.exitCode = await runCli();
  }
}
