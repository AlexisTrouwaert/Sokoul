// Drives the running app through its tools, and keeps a report of what really happened.
import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const data = join(process.env.APPDATA, 'SokoulPC')
const keys = join(data, 'debug-keys.txt')
const results = join(data, 'debug-results')
const live = join(here, 'live.ps1')
export const sleep = ms => new Promise(r => setTimeout(r, ms))

let counter = 0
const checks = []
let group = ''

export function section(name) {
    group = name
    console.log(`\n== ${name}`)
}

export function check(what, ok, detail = '') {
    checks.push({ group, what, ok: !!ok, detail: String(detail).slice(0, 300) })
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}${detail !== '' ? '  [' + String(detail).slice(0, 200) + ']' : ''}`)
    return !!ok
}

/** What could not be established here: it is reported apart, never counted as passed. */
export function unknown(what, detail = '') {
    checks.push({ group, what, ok: null, detail: String(detail).slice(0, 300) })
    console.log(`????  ${what}${detail !== '' ? '  [' + String(detail).slice(0, 200) + ']' : ''}`)
}

async function send(line) {
    for (let i = 0; i < 200 && existsSync(keys); i++) await sleep(25)
    writeFileSync(keys + '.tmp', line + '\n')
    renameSync(keys + '.tmp', keys)
}

export async function tool(name, args = {}, timeoutMs = 60000) {
    const id = `${process.pid}-${++counter}`
    await send(`TOOL ${id} ${name} ${JSON.stringify(args)}`)
    const file = join(results, id + '.json')
    const end = Date.now() + timeoutMs
    while (Date.now() < end) {
        if (existsSync(file)) {
            const answer = JSON.parse(readFileSync(file, 'utf8'))
            rmSync(file)
            if (answer.error) throw new Error(`${name}: ${answer.error}`)
            return answer.value
        }
        await sleep(50)
    }
    throw new Error(`${name}: no answer in ${timeoutMs} ms`)
}

export const state = () => tool('get_state')

/** Waits for a condition on the state. Returns the state that met it, or null. */
export async function until(condition, timeoutMs = 30000, gapMs = 300) {
    const end = Date.now() + timeoutMs
    let last = null
    while (Date.now() < end) {
        last = await state()
        if (condition(last)) return last
        await sleep(gapMs)
    }
    return null
}

export async function keys_(...list) {
    for (const k of list) {
        await send(k)
        await sleep(420)
    }
}

function ps(...args) {
    return spawnSync('powershell', ['-NoProfile', '-File', live, ...args], { encoding: 'utf8' }).stdout ?? ''
}

export function capture(name) {
    mkdirSync(join(here, '..', 'renders', 'live'), { recursive: true })
    ps('-Name', `verify_${name}`, '-WaitMs', '0')
}

export function startApp(faults = '', lang = '') {
    ps('-Stop')
    if (faults) process.env.SOKOUL_FAULTS = faults
    else delete process.env.SOKOUL_FAULTS
    const args = ['-Start', '-WaitMs', '0']
    if (lang) args.push('-Lang', lang)
    ps(...args)
    delete process.env.SOKOUL_LANG
}

export function stopApp() {
    ps('-Stop')
}

export function crashed() {
    return existsSync(join(data, 'crash.log'))
}

export function appMemoryMb() {
    const out = execFileSync('powershell', ['-NoProfile', '-Command', "(Get-Process | Where-Object { $_.ProcessName -eq 'Sokoul PC' -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1).WorkingSet64"], { encoding: 'utf8' })
    return Math.round(Number(out.trim()) / 1048576)
}

export function audioPeak() {
    const out = execFileSync('powershell', ['-NoProfile', '-File', join(here, 'audio-peak.ps1')], { encoding: 'utf8' })
    return JSON.parse(out.trim())
}

export function report(file) {
    const passed = checks.filter(c => c.ok === true).length
    const failed = checks.filter(c => c.ok === false)
    const open = checks.filter(c => c.ok === null)
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, JSON.stringify({ passed, failed: failed.length, unknown: open.length, checks }, null, 1))
    console.log(`\n${passed} passed, ${failed.length} failed, ${open.length} not established`)
    failed.forEach(c => console.log(`  FAIL ${c.group}: ${c.what} [${c.detail}]`))
    open.forEach(c => console.log(`  ???? ${c.group}: ${c.what} [${c.detail}]`))
}
