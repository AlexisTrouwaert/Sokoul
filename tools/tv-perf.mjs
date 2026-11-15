// How smooth the TV app really is: the same walk with the remote, and the system's own frame figures.
import { pathToFileURL } from 'node:url'
const t = await import(pathToFileURL(process.cwd() + '/tools/tv-lib.mjs').href)
const PKG = 'com.sokoul.tv'
const pick = (text, re) => text.match(re)?.[1] ?? '?'

function report(name) {
    const g = t.adb('shell', 'dumpsys', 'gfxinfo', PKG)
    console.log(name.padEnd(12),
        'images', pick(g, /Total frames rendered: (\d+)/).padStart(5),
        '| saccadees', pick(g, /Janky frames: \d+ \(([\d.]+%)\)/).padStart(7),
        '| 50%', pick(g, /50th percentile: (\d+ms)/).padStart(6),
        '| 90%', pick(g, /90th percentile: (\d+ms)/).padStart(6),
        '| 99%', pick(g, /99th percentile: (\d+ms)/).padStart(7),
        '| GPU 90%', pick(g, /90th gpu percentile: (\d+ms)/).padStart(6))
}
const reset = () => t.adb('shell', 'dumpsys', 'gfxinfo', PKG, 'reset')
async function walk(name, keys, gap = 350) {
    reset()
    for (const k of keys) {
        await t.keys(k)
        await t.sleep(gap)
    }
    await t.sleep(600)
    report(name)
}

await t.keys('DOWN', 'UP')
await t.tool('go_to', { section: 'home' }); await t.sleep(6000)
await walk('hero', ['RIGHT', 'RIGHT', 'RIGHT', 'LEFT', 'LEFT', 'LEFT'])
await walk('descente', Array(10).fill('DOWN'))
await walk('rangee', [...Array(10).fill('RIGHT'), ...Array(10).fill('LEFT')], 200)
await walk('remontee', Array(10).fill('UP'))
await t.tool('go_to', { section: 'movies' }); await t.sleep(5000)
await walk('films', [...Array(6).fill('DOWN'), ...Array(6).fill('RIGHT')])
const hit = (await t.tool('search_titles', { query: 'Inception' })).find(h => h.kind === 'movie')
reset(); await t.tool('open_title', { kind: 'movie', id: hit.id }); await t.sleep(3000); report('ouvre fiche')
await t.keys('DOWN'); await t.sleep(2500)
await walk('fiche', Array(5).fill('DOWN'))
await t.tool('back'); await t.sleep(1000)
await t.tool('go_to', { section: 'tv' }); await t.sleep(6000); await t.keys('DOWN'); await t.sleep(1000)
await walk('guide', Array(12).fill('DOWN'), 250)
await t.keys('BACK').catch(() => {})
const mem = t.adb('shell', 'dumpsys', 'meminfo', PKG).split('\n').filter(l => /TOTAL PSS/.test(l)).map(l => l.trim().replace(/\s+/g, ' ')).join('')
console.log('memoire', mem.slice(0, 60))
console.log(t.adb('shell', 'top', '-b', '-n', '1', '-q', '-o', '%CPU,ARGS').split('\n').slice(0, 4).map(l => l.trim().slice(0, 60)).join(' | '))
