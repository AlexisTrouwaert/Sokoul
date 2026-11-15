// Live checks on the Google TV over adb: the app's tools, the remote, and what the screen shows.
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'

const DEVICE = process.env.SOKOUL_TV ?? '192.168.0.108:5555'
const FILES = '/sdcard/Android/data/com.sokoul.tv/files'
const LOCAL = 'renders/tv'
const env = { ...process.env, MSYS_NO_PATHCONV: '1' }
export const sleep = ms => new Promise(r => setTimeout(r, ms))
export const adb = (...a) => execFileSync('adb', ['-s', DEVICE, ...a], { env, encoding: 'utf8', maxBuffer: 1 << 26, stdio: ['ignore', 'pipe', 'ignore'] })
let n = Date.now()

export async function tool(name, args = {}, timeoutMs = 90000) {
    const id = `t${n++}`
    mkdirSync(LOCAL, { recursive: true })
    const tmp = `${LOCAL}/cmd.txt`
    writeFileSync(tmp, `${id} ${name} ${JSON.stringify(args)}\n`)
    adb('shell', 'rm', '-f', `${FILES}/debug-tools.txt`)
    adb('push', tmp, `${FILES}/debug-tools.txt`)
    const end = Date.now() + timeoutMs
    let sentAt = Date.now()
    while (Date.now() < end) {
        await sleep(500)
        // The system sometimes refuses the file to the app for a moment: the command is sent again, a read one costs nothing.
        if (Date.now() - sentAt > 12000 && (name === 'get_state' || name === 'list_versions')) {
            adb('shell', 'rm', '-f', `${FILES}/debug-tools.txt`)
            adb('push', tmp, `${FILES}/debug-tools.txt`)
            sentAt = Date.now()
        }
        try {
            const text = adb('shell', 'cat', `${FILES}/debug-results/${id}.json`)
            if (text.startsWith('{')) {
                const o = JSON.parse(text)
                if (o.error) throw new Error(`${name}: ${o.error}`)
                return o.value
            }
        } catch (e) {
            if (e.message.startsWith(name)) throw e
        }
    }
    throw new Error(`${name}: no answer`)
}
export const state = () => tool('get_state')

const CODES = { UP: 19, DOWN: 20, LEFT: 21, RIGHT: 22, OK: 23, BACK: 4, PLAY: 85 }

/** The remote. Refuses unless Sokoul is on screen: blind keys have already opened other apps. */
export async function keys(...list) {
    const read = () => adb('shell', 'dumpsys', 'activity', 'activities').split(String.fromCharCode(10)).find(l => l.includes('topResumedActivity')) ?? ''
    for (const k of list) {
        let top = read()
        if (top.includes('dream')) {
            // The screensaver: waking the TV presses nothing.
            adb('shell', 'input', 'keyevent', '224')
            await sleep(1500)
            top = read()
        }
        if (!top.includes('com.sokoul.tv')) throw new Error('Sokoul is not in the foreground: ' + top.trim().slice(0, 90))
        adb('shell', 'input', 'keyevent', String(CODES[k] ?? k))
        await sleep(450)
    }
}

export function shot(name) {
    mkdirSync(LOCAL, { recursive: true })
    const data = execFileSync('adb', ['-s', DEVICE, 'exec-out', 'screencap', '-p'], { env, maxBuffer: 1 << 26 })
    writeFileSync(`${LOCAL}/${name}.png`, data)
}

export const crashes = () => adb('logcat', '-d').split('\n').filter(l => l.includes('AndroidRuntime') || l.includes('SokoulCrash')).slice(0, 8).map(l => l.slice(31, 170)).join('\n')
export const exists = existsSync
export const read = readFileSync
