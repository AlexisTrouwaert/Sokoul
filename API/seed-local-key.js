'use strict';
/**
 * Creates the access key of this local Mainapi (once) and writes it to %LOCALAPPDATA%\SokoulApi\access.key.
 * The key lets the app ask Mainapi to resolve embed pages into playable links. It never leaves this PC's database
 * and is never printed. Run after Mainapi has created its tables: `node API\seed-local-key.js`.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const mysql = require(path.join(__dirname, 'Mainapi', 'node_modules', 'mysql2', 'promise'));

const dir = path.join(process.env.LOCALAPPDATA || __dirname, 'SokoulApi');
const keyFile = path.join(dir, 'access.key');

function loadEnv() {
  const file = path.join(dir, 'local.env');
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)=(.*)$/);
    if (m) process.env[m[1]] = m[2];
  }
}

(async () => {
  loadEnv();
  const connection = await mysql.createConnection({
    host: process.env.DB_HOST, port: Number(process.env.DB_PORT), user: process.env.DB_USER,
    password: process.env.DB_PASSWORD, database: process.env.DB_NAME,
  });
  try {
    let key = fs.existsSync(keyFile) ? fs.readFileSync(keyFile, 'utf8').trim() : '';
    if (!key) {
      key = `sokoul-${crypto.randomBytes(24).toString('hex')}`;
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(keyFile, key, { mode: 0o600 });
    }
    await connection.execute(
      'INSERT INTO access_keys (key_value, active, used, duree_validite, expires_at) VALUES (?, 1, 1, ?, NULL) '
      + 'ON DUPLICATE KEY UPDATE active = 1',
      [key, 'illimité'],
    );
    console.log(`Access key ready (${keyFile}).`);
  } finally {
    await connection.end();
  }
})().catch((error) => { console.error(`Seeding failed: ${error.message}`); process.exit(1); });
