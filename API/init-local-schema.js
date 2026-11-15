'use strict';
/**
 * Creates the tables Mainapi expects but does not create itself (films, series, ...) in the local database, from the
 * manifest in Mainapi/db/schema. Only CREATE TABLE operations are applied. Run once, after the containers are up:
 * `node API\init-local-schema.js`. Reads %LOCALAPPDATA%\SokoulApi\local.env; prints no credential.
 */
const fs = require('node:fs');
const path = require('node:path');
const mysql = require(path.join(__dirname, 'Mainapi', 'node_modules', 'mysql2', 'promise'));
const { schema } = require(path.join(__dirname, 'Mainapi', 'db', 'schema'));
const { planSchema } = require(path.join(__dirname, 'Mainapi', 'db', 'schemaPlanner'));
const { loadSchemaMetadata } = require(path.join(__dirname, 'Mainapi', 'db', 'schemaMetadata'));

const envFile = path.join(process.env.LOCALAPPDATA || __dirname, 'SokoulApi', 'local.env');
for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
  const m = line.match(/^\s*([A-Z0-9_]+)=(.*)$/);
  if (m) process.env[m[1]] = m[2];
}

(async () => {
  const connection = await mysql.createConnection({
    host: process.env.DB_HOST, port: Number(process.env.DB_PORT), user: process.env.DB_USER,
    password: process.env.DB_PASSWORD, database: process.env.DB_NAME,
  });
  try {
    const plan = planSchema(schema, await loadSchemaMetadata(connection));
    const creates = plan.operations.filter((op) => op.id.startsWith('create_table'));
    for (const op of creates) {
      await connection.query(op.sql);
      console.log(`applied ${op.id}`);
    }
    console.log(`${creates.length} table(s) created, ${plan.operations.length - creates.length} other operation(s) skipped, ${plan.drift.length} drift(s).`);
  } finally {
    await connection.end();
  }
})().catch((error) => { console.error(`Schema init failed: ${error.message}`); process.exit(1); });
