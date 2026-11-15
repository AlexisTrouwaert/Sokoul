'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

test('backup and restore preserve UTF-8, require confirmation and capture recovery state first', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sokoul-backup-test-'));
  const config = path.join(root, 'SokoulApi');
  fs.mkdirSync(config);
  fs.writeFileSync(path.join(config, 'local.env'), 'DB_NAME=sokoul\nDB_PASSWORD=private-test-password\n');
  fs.writeFileSync(path.join(config, 'access.key'), 'private-test-key');
  const calls = path.join(root, 'calls.jsonl');
  const helper = path.join(root, 'fake-docker.cjs');
  fs.writeFileSync(helper, `
    const cp = require('node:child_process');
    const spawn = cp.spawn;
    cp.spawn = (command, args, options) => {
      if (command !== 'docker') throw new Error('Unexpected command');
      require('node:fs').appendFileSync(process.env.TEST_CALLS, JSON.stringify(args) + '\\n');
      const dump = args.some(a => a.includes('mysqldump'));
      const restore = args.some(a => a.includes('exec mysql -uroot'));
      return spawn(process.execPath, ['-e', dump
        ? 'process.stdout.write("-- UTF-8: café 日本語\\\\n");'
        : restore ? 'process.stdin.resume();' : ''], options);
    };
  `);
  const script = path.resolve(__dirname, '..', 'backup-backend.js');
  function run(args) {
    return spawnSync(process.execPath, ['--require', helper, script, ...args], {
      env: { ...process.env, LOCALAPPDATA: root, TEST_CALLS: calls }, encoding: 'utf8', windowsHide: true,
    });
  }
  try {
    const backup = run([]);
    assert.equal(backup.status, 0, backup.stderr);
    assert.doesNotMatch(backup.stdout + backup.stderr, /private-test/);
    const snapshots = fs.readdirSync(path.join(config, 'backups'));
    assert.equal(snapshots.length, 1);
    const snapshot = path.join(config, 'backups', snapshots[0]);
    assert.match(fs.readFileSync(path.join(snapshot, 'database.sql'), 'utf8'), /café 日本語/);
    assert.equal(JSON.parse(fs.readFileSync(path.join(snapshot, 'complete.json'))).version, 1);

    const before = fs.readFileSync(calls, 'utf8');
    assert.equal(run(['--restore', snapshot]).status, 1);
    assert.equal(fs.readFileSync(calls, 'utf8'), before, 'Unconfirmed restore must not run Docker');

    fs.writeFileSync(path.join(config, 'access.key'), 'changed-key');
    const restored = run(['--restore', snapshot, '--confirm-restore']);
    assert.equal(restored.status, 0, restored.stderr);
    assert.equal(fs.readFileSync(path.join(config, 'access.key'), 'utf8'), 'private-test-key');
    const recovery = fs.readdirSync(path.join(config, 'backups')).find(name => name.startsWith('before-restore-'));
    assert.ok(recovery);
    assert.equal(fs.readFileSync(path.join(config, 'backups', recovery, 'access.key'), 'utf8'), 'changed-key');
    const actions = fs.readFileSync(calls, 'utf8').trim().split('\n').map(JSON.parse);
    assert.ok(actions[1].includes('stop'));
    assert.ok(actions[2].some(arg => arg.includes('mysqldump')));
    assert.ok(actions[3].some(arg => arg.includes('exec mysql -uroot')));

    fs.writeFileSync(path.join(snapshot, 'local.env'), 'DB_NAME=other\nDB_PASSWORD=other\n');
    const actionCount = actions.length;
    assert.equal(run(['--restore', snapshot, '--confirm-restore']).status, 1);
    assert.equal(fs.readFileSync(calls, 'utf8').trim().split('\n').length, actionCount);
  } finally {
    // Only this test-created directory is removed.
    fs.rmSync(root, { recursive: true, force: true });
  }
});
