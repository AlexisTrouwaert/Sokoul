import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildUserscript } from '../../userscript/build-userscript.mjs';
const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export async function buildExtractors() {
  const source = await readFile(path.join(directory, 'runtime/extractors-core.js'), 'utf8');
  const adapter = source + '\nglobalThis.MovixExtractors = globalThis.MovixExtractionCore.createExtractors(globalThis);\n';
  await Promise.all(['Chrome', 'Firefox'].map(platform => writeFile(path.join(directory, platform, 'extractors.js'), adapter)));
  await buildUserscript(source);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await buildExtractors();
