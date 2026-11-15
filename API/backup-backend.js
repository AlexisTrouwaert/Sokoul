'use strict';

// Local MySQL recovery. Secrets and SQL stay outside the source checkout.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { pipeline } = require('node:stream/promises');

const configDir = path.join(process.env.LOCALAPPDATA || '', 'SokoulApi');
const envFile = path.join(configDir, 'local.env');
const composeFile = path.join(__dirname, 'docker-compose.yml');
const args = process.argv.slice(2);
const restore = args[0] === '--restore';

function runDocker(command, input, output) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', ['compose', '--env-file', envFile, '-f', composeFile, ...command], {
      env: { ...process.env, SOKOUL_ENV_FILE: envFile },
      stdio: [input ? 'pipe' : 'ignore', output ? 'pipe' : 'ignore', 'pipe'],
      windowsHide: true,
    });
    // Do not relay stderr: command failures can contain sensitive data.
    child.stderr.resume();
    const streams = [];
    if (input) streams.push(pipeline(input, child.stdin));
    if (output) streams.push(pipeline(child.stdout, output));
    child.once('error', () => reject(new Error('Cannot launch Docker. Check Docker Desktop.')));
    child.once('close', (code) => {
      if (code !== 0) reject(new Error('Docker database operation failed. The backup is not valid until complete.json exists.'));
      else Promise.all(streams).then(resolve, reject);
    });
    // Attach rejection handlers immediately, even if Docker fails before close.
    for (const stream of streams) stream.catch(() => {});
  });
}

function readConfig(file) {
  return Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
    .map(line => line.match(/^([A-Z0-9_]+)=(.*)$/)).filter(Boolean).map(match => [match[1], match[2]]));
}

async function main() {
  if (args[0] === '--help') {
    console.log('Backup: node API/backup-backend.js [destination]\nRestore: node API/backup-backend.js --restore <backup-directory> --confirm-restore\nBackups contain secrets. Keep them private. Restore requires the same local MySQL configuration.');
    return;
  }
  if (!process.env.LOCALAPPDATA || !fs.existsSync(envFile)) throw new Error('Local backend configuration is missing.');
  if (restore && (args.length !== 3 || args[2] !== '--confirm-restore')) {
    throw new Error('Restore replaces backend SQL data. Use --restore <backup-directory> --confirm-restore.');
  }
  if (!restore && args.length > 1) throw new Error('Supply at most one backup destination.');
  const target = path.resolve(restore ? args[1] : (args[0] || path.join(configDir, 'backups', new Date().toISOString().replace(/[:.]/g, '-'))));
  const relative = path.relative(path.resolve(__dirname, '..'), target);
  if (!relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative)) {
    throw new Error('Store private backups outside the source checkout.');
  }
  if (!restore) {
    fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
    fs.mkdirSync(target, { recursive: false, mode: 0o700 });
    fs.copyFileSync(envFile, path.join(target, 'local.env'), fs.constants.COPYFILE_EXCL);
    if (fs.existsSync(path.join(configDir, 'access.key'))) fs.copyFileSync(path.join(configDir, 'access.key'), path.join(target, 'access.key'));
    await runDocker(['exec', '-T', 'mysql', 'sh', '-c',
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --no-tablespaces --databases "$MYSQL_DATABASE"'],
    null, fs.createWriteStream(path.join(target, 'database.sql'), { flags: 'wx', mode: 0o600 }));
    fs.writeFileSync(path.join(target, 'complete.json'), JSON.stringify({ version: 1, createdAt: new Date().toISOString() }) + '\n', { flag: 'wx', mode: 0o600 });
    console.log(`Backend backup complete: ${target}`);
  } else {
    const manifest = JSON.parse(fs.readFileSync(path.join(target, 'complete.json'), 'utf8'));
    if (manifest.version !== 1 || !fs.statSync(path.join(target, 'database.sql')).size) throw new Error('Unsupported or incomplete backup.');
    const current = readConfig(envFile);
    const saved = readConfig(path.join(target, 'local.env'));
    if (['DB_NAME', 'DB_PASSWORD'].some(key => !current[key] || current[key] !== saved[key])) {
      throw new Error('Backup belongs to another MySQL configuration. Restore its configuration through the documented manual recovery procedure.');
    }
    // Refuse silent loss of pre-restore state; always create an independent recovery dump.
    const recoveryDir = path.join(configDir, 'backups', `before-restore-${Date.now()}`);
    fs.mkdirSync(recoveryDir, { recursive: true, mode: 0o700 });
    await runDocker(['--profile', 'web', 'stop', 'mainapi', 'proxiesembed', 'watchparty', 'web']);
    await runDocker(['exec', '-T', 'mysql', 'sh', '-c',
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --no-tablespaces --databases "$MYSQL_DATABASE"'],
    null, fs.createWriteStream(path.join(recoveryDir, 'database.sql'), { flags: 'wx', mode: 0o600 }));
    fs.copyFileSync(envFile, path.join(recoveryDir, 'local.env'));
    const keyFile = path.join(configDir, 'access.key');
    if (fs.existsSync(keyFile)) fs.copyFileSync(keyFile, path.join(recoveryDir, 'access.key'));
    fs.writeFileSync(path.join(recoveryDir, 'complete.json'), JSON.stringify({ version: 1, createdAt: new Date().toISOString() }) + '\n');
    await runDocker(['exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],
      fs.createReadStream(path.join(target, 'database.sql')));
    if (fs.existsSync(path.join(target, 'access.key'))) fs.copyFileSync(path.join(target, 'access.key'), keyFile);
    console.log(`Database restored; services remain stopped. Pre-restore backup: ${recoveryDir}`);
    console.log('Run API/start-backend.ps1 to initialize and start services. Client profiles and recordings are not part of this backend backup.');
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
