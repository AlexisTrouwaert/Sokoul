'use strict';

// Stream active-checkout runtime bytes into ordinary files for Gradle on Windows cloud folders.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const source = path.resolve(__dirname, '../app/app-resources/windows/vlc');
const root = fs.realpathSync(source);
const target = fs.mkdtempSync(path.join(os.tmpdir(), 'sokoul-vlc-'));
function copy(directory, destination) {
  fs.mkdirSync(destination, { recursive: true });
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const input = path.join(directory, entry.name);
    const relative = path.relative(root, fs.realpathSync(input));
    if (relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) throw new Error('VLC source escapes the active runtime directory.');
    const output = path.join(destination, entry.name);
    if (fs.statSync(input).isDirectory()) copy(input, output);
    else fs.writeFileSync(output, fs.readFileSync(input));
  }
}
try {
  copy(source, target);
  if (!fs.statSync(path.join(target, 'libvlc.dll')).size) throw new Error('Missing libVLC runtime.');
  console.log(target);
} catch {
  console.error('Cannot materialize the active libVLC runtime. Check readable source files and TEMP storage.');
  process.exitCode = 1;
}
