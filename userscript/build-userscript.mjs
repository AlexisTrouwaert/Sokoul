import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
export async function buildUserscript(coreSource) {
  const prefix = await readFile(path.join(directory, 'src/compat-prefix.js'), 'utf8');
  const suffix = await readFile(path.join(directory, 'src/compat-suffix.js'), 'utf8');
  const source = coreSource ?? await readFile(path.join(directory, '../extension/runtime/extractors-core.js'), 'utf8');
  const quickjs = await readFile(path.join(directory, '../extension/Chrome/fsvid-vidzy-quickjs.js'), 'utf8');
  const adapter = `
  // Shared algorithms with the existing GM transport and page compatibility layer.
  ${quickjs}
  ${source}
  globalThis.MovixExtractors = globalThis.MovixExtractionCore.createExtractors(
    Object.assign(Object.create(globalThis), { fetch })
  );
`;
  await writeFile(path.join(directory, 'movix.user.js'), prefix + adapter + suffix);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await buildUserscript();
