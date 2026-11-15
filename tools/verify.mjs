// Live verification of the running app: node tools/verify.mjs <part> [...]. Parts: start titles sagas player faults modes
// download trailers input soak english. Each writes renders/verify/<part>.json, and says what it could not establish.
import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { appMemoryMb, audioPeak, capture, check, crashed, keys_, report, section, sleep, startApp, state, stopApp, tool, unknown, until } from './verify-lib.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const INCEPTION = { kind: 'movie', id: 27205 }

async function ready(faults = '', lang = '') {
    startApp(faults, lang)
    for (let i = 0; i < 120; i++) {
        try {
            const s = await tool('get_state', {}, 3000)
            if (s.section) return s
        } catch { /* not up yet */ }
        await sleep(1000)
    }
    throw new Error('the app did not come up')
}

const phase = s => s.player.phase.name
const engine = s => s.player.engine ?? {}
const s_playing = s => phase(s) === 'Playing'

async function clearPlayer() {
    let s = await state()
    for (let i = 0; i < 6 && s.pages.length > 0; i++) {
        await tool('back')
        await sleep(300)
        s = await state()
    }
}

function ytDlp() {
    const base = join(process.env.LOCALAPPDATA, 'Microsoft', 'WinGet', 'Packages')
    const dir = readdirSync(base).find(d => d.startsWith('yt-dlp.yt-dlp'))
    return join(base, dir, 'yt-dlp.exe')
}

const parts = {
    // The home page redesign (openspec/changes/tv-home-redesign): loading shapes, trailer rules, focus, row length,
    // "Tonight", and the tint of a title page. What only a picture can show is captured for a look.
    home: async () => {
        section('home')
        startApp()
        // As soon as the app answers: the page may still be loading, with its shapes.
        for (let i = 0; i < 120; i++) {
            try { if ((await tool('get_state', {}, 3000)).section) break } catch { /* not up yet */ }
            await sleep(500)
        }
        capture('home_loading')
        await sleep(9000)
        capture('home_loaded')
        check('pas de plantage au chargement', !crashed())

        // Trailer: a few seconds of steady focus on the hero, then it plays silent.
        const t = await until(s => s.trailer.playing, 40000, 500).catch(() => null)
        if (!t) unknown('bande-annonce du héros', 'aucune n’a démarré en 40 s (réglage, réseau ou titre sans bande-annonce)')
        else {
            const s = await state()
            check('la bande-annonce du héros est muette par défaut', s.player.engine.muted === true, `muted=${s.player.engine.muted}`)
            const peak = audioPeak()
            check('aucun son ne sort pendant la bande-annonce', peak.max < 0.02, `peak=${peak.max}`)
            // Play, My List, About, Next, Tonight (when there is history), Sound, Stop: Right to the end, then OK.
            await keys_('RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT')
            capture('home_stop_button')
            await keys_('ENTER')
            await sleep(1500)
            check('le bouton Arrêter coupe la bande-annonce', !(await state()).trailer.playing)
            await sleep(5000)
            check('elle ne revient pas sur la même diapo', !(await state()).trailer.playing)
            capture('home_after_stop')
        }

        // Rows: ten titles then "See All"; coming back up lands on the card left there.
        // Fifth card of a row, up into the row above and two cards further right, then down again: the row gives back its
        // fifth card, not the one nearest to where focus comes from.
        await keys_('DOWN', 'DOWN', 'DOWN')
        await keys_('RIGHT', 'RIGHT', 'RIGHT', 'RIGHT')
        capture('home_row_fifth')
        await keys_('UP', 'RIGHT', 'RIGHT')
        capture('home_row_above')
        await keys_('DOWN')
        capture('home_row_back')
        await keys_('RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT')
        capture('home_row_end')
        await keys_('RIGHT')
        capture('home_dead_end')
        const before = (await state()).pages.length
        await keys_('ENTER')
        await sleep(2500)
        const after = await state()
        check('« Tout voir » ouvre la liste complète', after.pages.length > before, JSON.stringify(after.pages).slice(0, 120))
        capture('home_see_all')
        await keys_('ESC')
        await sleep(800)

        // A title page takes a trace of its artwork's colour.
        await tool('open_title', INCEPTION)
        await sleep(4000)
        capture('title_ambient')
        await tool('back')
        check('pas de plantage', !crashed())
    },
    async start() {
        section('Démarrage et clés')
        const s = await ready()
        check('11 services configurés', Object.values(s.services).filter(Boolean).length === 11, JSON.stringify(s.services))
        check("l'état ne contient aucune adresse", !JSON.stringify(s).includes('http'))
        const home = await until(x => x.section === 'home', 5000)
        check('accueil affiché', !!home)
        section('Écrans')
        for (const name of ['library', 'movies', 'series', 'collections', 'search', 'settings', 'home']) {
            await tool('go_to', { section: name })
            await sleep(3500)
            capture(`screen_${name}`)
            const now = await state()
            check(`section ${name}`, now.section === name && !crashed())
        }
        const found = await tool('search_titles', { query: 'Dune' })
        check('la recherche renvoie des titres', found.length > 3, found.slice(0, 3).map(t => t.title).join(', '))
    },

    async search() {
        section('Global search and navigation restoration')
        await ready()
        await tool('go_to', { section: 'movies' })
        await tool('go_to', { section: 'search' })
        await sleep(900)
        await keys_('D', 'U', 'N', 'E')
        await sleep(9000)
        capture('search_all_dune')
        let current = await state()
        check('global search remains active after typing', current.section === 'search' && !crashed(), current.section)

        await keys_('CLICK:800:625')
        await sleep(2500)
        current = await state()
        check('selecting a result opens its detail page', current.section === 'search' && current.pages.length > 0, JSON.stringify(current.pages))
        capture('search_result_detail')
        await tool('back')
        await sleep(1000)
        capture('search_restored_after_detail')

        await tool('go_to', { section: 'home' })
        await tool('go_to', { section: 'search' })
        await sleep(2500)
        capture('search_restored_dune')
        current = await state()
        check('search section restores after switching away', current.section === 'search' && !crashed(), current.section)

        await keys_('ESC')
        await sleep(800)
        current = await state()
        check('Escape returns to the section that opened search', current.section === 'home', current.section)
    },

    async titles() {
        section('Fiches, bandes-annonces en version originale, langues réelles')
        await ready()
        const cases = [
            ['film américain', 'movie', 27205, 'en'], ['film français', 'movie', 77338, 'fr'], ['film coréen', 'movie', 496243, 'ko'],
            ['film japonais', 'movie', 129, 'ja'], ['film espagnol', 'movie', 1417, 'es'], ['série américaine', 'tv', 1396, 'en'],
            ['série française', 'tv', 96677, 'fr'], ['série coréenne', 'tv', 93405, 'ko'], ['film récent', 'movie', 693134, 'en'],
        ]
        const yt = ytDlp()
        for (const [label, kind, id, lang] of cases) {
            const t = await tool('title_info', { kind, id })
            check(`${label} : ${t.title}, langue originale ${lang}`, t.originalLanguage === lang, t.originalLanguage)
            const top = t.trailers[0]
            if (!top) {
                unknown(`${t.title} : aucune bande-annonce sur TMDB`)
                continue
            }
            const localized = /\b(vf|vostfr|canal|ocs|bande[- ]annonce)\b/i.test(top.name) && lang !== 'fr'
            check(`${t.title} : la première vidéo est dans la langue originale ou en anglais`, (top.language === lang || top.language === 'en') && !localized, `${top.language} « ${top.name} »`)
            // As the app does: a video made private since TMDB listed it gives way to the next candidate.
            let measured = 'failed'
            let skipped = 0
            for (const video of t.trailers.slice(0, 4)) {
                try {
                    measured = execFileSync(yt, ['--no-warnings', '--no-playlist', '--js-runtimes', 'node', '-f', 'bv*[height<=1080][vcodec!^=av01]+ba[ext=m4a]/b[height<=1080]/b', '-S', 'lang,res:1080,tbr', '--print', '%(height)s|%(language)s', '--', video.key], { encoding: 'utf8', timeout: 90000, stdio: ['ignore', 'pipe', 'ignore'] }).trim()
                    break
                } catch (e) {
                    skipped++
                }
            }
            if (skipped > 0 && measured !== 'failed') measured += ` (${skipped} indisponible écartée)`
            const height = Number(measured.split('|')[0])
            check(`${t.title} : qualité réelle de la vidéo`, height >= 720, measured)
            check(`${t.title} : notes et plateformes`, t.imdb != null || t.offers.length > 0, `IMDb ${t.imdb}, RT ${t.rottenTomatoes}, ${t.offers.slice(0, 3).join(', ')}`)
        }
        section('Épisodes')
        const eps = await tool('season_info', { id: 96677, season: 1 })
        check('Lupin : chaque épisode a un résumé et une image', eps.length > 0 && eps.every(e => e.hasOverview && e.hasImage), `${eps.length} épisodes`)
        section('Appartenance à une saga')
        const dune = await tool('title_info', { kind: 'movie', id: 438631 })
        check('Dune affiche sa saga', !!dune.saga, dune.saga)
        const alone = await tool('title_info', { kind: 'movie', id: 496243 })
        check('Parasite n’en affiche aucune', !alone.saga, alone.saga)
    },

    async sagas() {
        section('Les sagas')
        await ready()
        const list = await tool('list_sagas', {}, 120000)
        const names = Object.values(list.shelves).flat()
        check('toutes les sagas sont rangées, sans doublon', new Set(names).size > 700, `${names.length} noms, ${new Set(names).size} distincts, ${Object.keys(list.shelves).length} catégories`)
        for (const [category, entries] of Object.entries(list.shelves)) console.log(`   ${category}: ${entries.length} (${entries.slice(0, 6).join(', ')})`)
        const sample = Object.values(list.shelves).flatMap(entries => entries.filter((_, i) => i % 25 === 3).slice(0, 5))
        for (const name of [...list.curated, ...sample]) {
            try {
                const p = await tool('saga_info', { name }, 180000)
                const details = `${p.films.length} films, ${p.rooms.join('/')}, résumé ${p.overview?.length ?? 0} (${p.overviewSource ?? 'TMDB'}), BO ${p.boxOffice}, ${p.cast} acteurs, ${p.gallery} images`
                check(`${name}`, p.films.length >= 1 && p.rooms.includes('Films') && (p.overview?.length ?? 0) > 40, details)
                if (!p.hasLogo) unknown(`${name} : pas de logo, le nom s'affiche en texte`)
                if (p.overview && /\[[^\]]*[ɑɔɛəɪʊʃʒθðŋɹˈˌː]/.test(p.overview)) check(`${name} : pas de prononciation dans le résumé`, false, p.overview.slice(0, 80))
                console.log(`      « ${(p.overview ?? '').slice(0, 110).replace(/\n/g, ' ')} »`)
                if (p.storyOrder.length) console.log(`      récit (${p.storyOrder.length}) : ${p.storyOrder.join(' > ')}`)
                const absent = p.films.filter(f => p.storyOrder.length && !p.storyOrder.includes(f))
                check(`${name} : tous les films sont dans l’ordre du récit`, absent.length === 0, absent.join(', '))
            } catch (e) {
                check(`${name}`, false, e.message)
            }
        }
    },

    async player() {
        section('Lecture réelle')
        await ready()
        await tool('play', INCEPTION)
        const started = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        if (!check('le film démarre', !!started, started?.player.current)) return
        const t0 = engine(started).timeMs
        await sleep(6000)
        const s1 = await state()
        check('le temps avance', engine(s1).timeMs > t0 + 3000, `${t0} -> ${engine(s1).timeMs}`)
        check('image décodée', !!engine(s1).video, engine(s1).video)
        check('film de longue durée', engine(s1).lengthMs > 3600000, engine(s1).lengthMs)
        capture('player_playing')
        section('Son réel')
        const peak = audioPeak()
        check('du son sort de la carte son pendant le film', peak.max > 0.005, JSON.stringify(peak))
        check('pistes audio lues', engine(s1).audio.length > 0, engine(s1).audio.map(a => a.label).join(', '))
        section('Pause et déplacement')
        await tool('pause')
        await sleep(1500)
        const p1 = await state()
        await sleep(2000)
        const p2 = await state()
        check('la pause fige le temps', engine(p2).paused && Math.abs(engine(p2).timeMs - engine(p1).timeMs) < 500)
        check('pause : aucune bascule automatique', p2.player.current === s1.player.current)
        await tool('pause')
        const before = engine(await state()).timeMs
        await tool('seek', { seconds: 300 })
        const moved = await until(s => engine(s).timeMs > before + 280000, 20000)
        check('avance de 5 min', !!moved, moved && engine(moved).timeMs)
        section('Sous-titres en ligne')
        await tool('load_subtitles')
        const loaded = await until(s => s.subtitles.found > 0 && !s.subtitles.searching, 30000)
        check('sous-titres trouvés', !!loaded, loaded?.subtitles.found)
        await tool('choose_subtitle', { index: 0 })
        const active = await until(s => !!s.subtitles.active, 30000)
        check('sous-titre chargé', !!active, active?.subtitles.active)
        const line = await until(s => !!s.subtitles.line, 90000, 500)
        check('une réplique s’affiche', !!line, line?.subtitles.line)
        if (line) capture('player_subtitle')
        await tool('shift_subtitles', { ms: 500 })
        check('le décalage s’applique', (await state()).subtitles.delayMs === 500)
        await tool('shift_subtitles', { ms: -500 })
        const usedKeys = await state()
        const position = 1 + engine(usedKeys).subtitles.filter(t => t.id >= 0).length + usedKeys.subtitles.found
        await keys_('DOWN')
        await sleep(800)
        await keys_(...Array(position + 1).fill('DOWN'))
        capture('player_subtitle_menu_end')
        await keys_('ENTER')
        await sleep(400)
        const byKeys = await state()
        check('le bouton de décalage répond au clavier', byKeys.subtitles.delayMs === -500 || byKeys.subtitles.delayMs === 500, byKeys.subtitles.delayMs)
        await keys_('ESC')
        await tool('clear_subtitles')
        check('sous-titres retirés', !(await state()).subtitles.active)
        section('Changement de version')
        const here0 = engine(await state()).timeMs
        const versions = await tool('list_versions')
        check('plusieurs versions', versions.length > 2, `${versions.length}, ${versions.slice(0, 3).map(v => `${v.summary} ${v.readiness}`).join(' | ')}`)
        const current = versions.find(v => v.current)
        const other = versions.find(v => !v.current && v.readiness !== 'NotCached' && v.origin === current.origin) ?? versions.find(v => !v.current && v.readiness !== 'NotCached')
        await tool('choose_version', { index: other.index })
        const switched = await until(s => s.player.current === other.summary && phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        if (switched) {
            check('la nouvelle version joue', true, other.summary)
            check('à la même position', Math.abs(engine(switched).timeMs - here0) < 45000, `${here0} -> ${engine(switched).timeMs}`)
        } else {
            const st = await state()
            unknown(`autre version non lancée : ${other.summary}`, JSON.stringify(st.player.phase))
        }
        section('Actualiser pendant le film')
        const beforeRefresh = (await state()).player.current
        await tool('refresh_versions')
        await sleep(4000)
        const afterRefresh = await until(s => !s.player.searching, 30000)
        check('le film continue', afterRefresh && phase(afterRefresh) === 'Playing' && afterRefresh.player.current === beforeRefresh)
        section('Retour')
        await tool('back')
        const closed = await until(s => s.pages.length === 0 && !engine(s).playing, 10000)
        check('le lecteur se ferme et le flux s’arrête', !!closed)
        await sleep(2000)
        const silence = audioPeak()
        check('plus aucun son après la fermeture', silence.max < 0.005, JSON.stringify(silence))
        check('pas de plantage', !crashed())
    },

    async versions() {
        section('Changer de version : chaque cause')
        await ready()
        await tool('play', INCEPTION)
        const s0 = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        if (!check('le film démarre', !!s0, s0?.player.current)) return
        const audio = engine(s0).audio
        const french = audio.filter(a => /french|fran/i.test(a.label))
        if (french.length) {
            await sleep(3000)
            const st = engine(await state())
            check('système français : piste française choisie dans un MULTI', french.some(a => a.id === st.audioId), `piste ${st.audioId} ; françaises : ${french.map(a => a.id).join(',')}`)
        } else {
            unknown('cette version n’a pas de piste française', audio.map(a => a.label).join(', '))
        }
        await tool('seek', { seconds: 600 })
        await until(s => engine(s).timeMs > 590000, 20000)
        const tried = new Set()
        for (let i = 0; i < 5; i++) {
            const list = await tool('list_versions')
            const other = list.find(v => !v.current && !v.failure && v.readiness === 'Likely' && !tried.has(v.summary))
            if (!other) break
            tried.add(other.summary)
            const at = engine(await state()).timeMs
            const started = Date.now()
            await tool('choose_version', { index: other.index })
            let stateAfter
            const phases = []
            const end = Date.now() + 100000
            while (Date.now() < end) {
                stateAfter = await state()
                const name = `${phase(stateAfter)}${stateAfter.player.phase.percent != null ? ' ' + stateAfter.player.phase.percent + '%' : ''}`
                if (phases[phases.length - 1] !== name) phases.push(name)
                if (phase(stateAfter) === 'Playing' && engine(stateAfter).timeMs > 0 && Date.now() - started > 1500) break
                await sleep(300)
            }
            const after = (await tool('list_versions')).find(v => v.summary === other.summary)
            const seconds = Math.round((Date.now() - started) / 1000)
            console.log(`   ${other.summary} : ${phases.join(' > ')}, ${seconds} s, cause ${after?.failure}, joue ${stateAfter.player.current}, position ${at} -> ${engine(stateAfter).timeMs}`)
            if (stateAfter.player.current === other.summary) {
                check(`${other.summary} joue, à la même position`, Math.abs(engine(stateAfter).timeMs - at) < 45000, `${at} -> ${engine(stateAfter).timeMs}, ${seconds} s`)
            } else {
                check(`${other.summary} : l’échec a une cause et le film reprend à la même position`, !!after?.failure && phase(stateAfter) === 'Playing' && Math.abs(engine(stateAfter).timeMs - at) < 45000, `${after?.failure}, ${at} -> ${engine(stateAfter).timeMs}`)
            }
        }
        await tool('back')
        await sleep(3000)
        const silence = audioPeak()
        check('plus aucun son après la fermeture', silence.active === 0, JSON.stringify(silence))
    },

    async faults() {
        section('Pannes réelles dans le vrai lecteur')
        await ready('refused,fake,stall')
        await tool('set_preference', { name: 'start', value: 'ReadyFirst' })
        await tool('play', INCEPTION)
        const seen = new Set()
        const played = await until(s => {
            if (s.player.current) seen.add(s.player.current)
            return phase(s) === 'Playing' && engine(s).timeMs > 0
        }, 120000)
        check('après 3 versions en panne, une vraie joue', !!played, played?.player.current)
        const list = await tool('list_versions')
        const failed = list.filter(v => v.failure).map(v => v.failure)
        check('refus, faux fichier et silence ont chacun leur cause', ['Refused(403)', 'FakeFile', 'TimedOut'].every(c => failed.includes(c)), failed.join(', '))
        capture('faults_versions')
        await tool('back')

        section('Tout en panne : écran d’échec')
        await ready('refused,only')
        await tool('play', INCEPTION)
        const down = await until(s => phase(s) === 'Failed', 60000)
        check('écran d’échec avec la cause', down?.player.phase.failure === 'Refused(403)', down?.player.phase.failure)
        capture('faults_failed')
        await keys_('ENTER')
        await sleep(2500)
        const again = await until(s => phase(s) === 'Failed', 30000)
        check('Réessayer relance vraiment', !!again)
        await keys_('RIGHT', 'ENTER')
        await sleep(1000)
        const panel = await state()
        check('Toutes les versions s’ouvre depuis l’échec', phase(panel) === 'Choosing', phase(panel))
        capture('faults_all_versions')
        await tool('back')

        section('Compte IPTV plein (réponse simulée, vrai déroulé)')
        await ready('full')
        await tool('set_preference', { name: 'start', value: 'ReadyFirst' })
        await tool('play', INCEPTION)
        const afterFull = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        const withFull = await tool('list_versions')
        check('le lecteur passe à une autre version', !!afterFull && withFull.some(v => (v.failure ?? '').startsWith('ConnectionsFull')), withFull.filter(v => v.failure).map(v => v.failure).join(', '))
        await tool('back')

        section('Flux coupé en plein film : libVLC réel, flux réel, coupure réelle')
        await ready('cut')
        await tool('play', INCEPTION)
        const cutPlaying = await until(s => phase(s) === 'Playing' && (s.player.current ?? '').includes('4K') && engine(s).timeMs > 0, 90000)
        check('le flux à couper joue', !!cutPlaying, cutPlaying?.player.current)
        let lastTime = 0
        const moved = await until(s => {
            if (engine(s).timeMs > 0 && s.player.current?.includes('4K')) lastTime = engine(s).timeMs
            return phase(s) === 'Playing' && !(s.player.current ?? '').startsWith('4K · VF') && engine(s).timeMs > 0
        }, 420000, 1000)
        const cutList = await tool('list_versions')
        const reason = cutList.find(v => v.failure)?.failure
        check('la coupure est détectée et une autre version prend le relais', !!moved, `cause ${reason}, ${moved?.player.current}`)
        if (moved) check('à la même position', Math.abs(engine(moved).timeMs - lastTime) < 60000, `${lastTime} -> ${engine(moved).timeMs}`)
        await tool('back')
        check('pas de plantage', !crashed())
    },

    async modes() {
        section('Les 3 modes de démarrage (versions simulées, film réel)')
        await ready('upgrade,only')
        const prefs = (await state()).preferences

        await tool('set_preference', { name: 'start', value: 'ReadyFirst' })
        await tool('play', INCEPTION)
        const ready1 = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        check('version prête : le film démarre sans attendre la 4K', !!ready1 && !ready1.player.current.startsWith('4K · VF'), ready1?.player.current)
        const offer = await until(s => s.player.upgrade?.ready === true, 60000)
        check('la meilleure version se télécharge derrière et est proposée', !!offer, JSON.stringify(offer?.player.upgrade))
        capture('modes_upgrade_offer')
        await tool('seek', { seconds: 120 })
        await sleep(3000)
        const at = engine(await state()).timeMs
        await tool('accept_upgrade')
        const up = await until(s => s.player.current?.startsWith('4K · VF') && phase(s) === 'Playing' && engine(s).timeMs > 0, 60000)
        check('la bascule se fait à la même position', !!up && Math.abs(engine(up).timeMs - at) < 45000, up && `${at} -> ${engine(up).timeMs}`)
        await tool('back')

        await tool('set_preference', { name: 'start', value: 'WaitBest' })
        await tool('play', INCEPTION)
        const percents = new Set()
        const best = await until(s => {
            if (phase(s) === 'Downloading') percents.add(s.player.phase.percent)
            return phase(s) === 'Playing' && engine(s).timeMs > 0
        }, 120000, 250)
        check('attendre la meilleure : le pourcentage monte', percents.size >= 3, [...percents].join(', '))
        check('puis le film démarre seul', !!best && best.player.current.startsWith('4K'), best?.player.current)
        await tool('back')

        await tool('set_preference', { name: 'start', value: 'Ask' })
        await tool('play', INCEPTION)
        const asked = await until(s => phase(s) === 'Choosing' && s.player.versions > 0, 30000)
        check('me demander : la liste s’ouvre', !!asked)
        await sleep(3000)
        check('et rien ne démarre', !engine(await state()).playing)
        capture('modes_ask')
        const list = await tool('list_versions')
        await tool('choose_version', { index: list.find(v => v.readiness !== 'NotCached' && !v.summary.startsWith('4K · VF')).index })
        const chosen = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        check('le choix démarre', !!chosen, chosen?.player.current)
        await tool('back')

        section('Comment regarder depuis une fiche, pendant la bande-annonce')
        await tool('set_preference', { name: 'start', value: 'ReadyFirst' })
        await ready()
        await tool('open_title', INCEPTION)
        await until(s => s.trailer.playing, 60000)
        await tool('play', { ...INCEPTION, choose_first: 'true' })
        const free = await until(s => phase(s) === 'Choosing' && s.player.versions > 0, 30000)
        check('la liste s’ouvre', !!free)
        const quiet = audioPeak()
        check('la bande-annonce se tait et rien ne démarre', quiet.active === 0 && !s_playing(await state()), JSON.stringify(quiet))
        await tool('back')
        await clearPlayer()

        section('IPTV réel : une seule connexion')
        await tool('set_preference', { name: 'origin', value: 'Iptv' })
        await tool('play', INCEPTION)
        const iptv = await until(s => (phase(s) === 'Playing' && engine(s).timeMs > 0) || phase(s) === 'Failed', 90000)
        if (iptv && phase(iptv) === 'Playing') {
            const versions = await tool('list_versions')
            const on = versions.find(v => v.summary === iptv.player.current)
            if (on?.origin === 'Iptv') check('un film IPTV joue', true, iptv.player.current)
            else unknown('le compte IPTV n’a pas servi', on?.origin + ' ' + iptv.player.current)
        } else unknown('IPTV indisponible maintenant, peut-être occupé ailleurs', JSON.stringify(iptv?.player.phase))
        await tool('back')
        await tool('set_preference', { name: 'origin', value: 'Automatic' })
        await clearPlayer()

        section('Préférences')
        for (const [name, value] of [['audio', 'Vo'], ['maxQuality', 'P1080'], ['origin', 'Iptv']]) {
            await tool('set_preference', { name, value })
            await tool('play', { ...INCEPTION, choose_first: 'true' })
            await until(s => !s.player.searching && s.player.versions > 0, 40000)
            const v = await tool('list_versions')
            console.log(`   ${name}=${value} : ${v.slice(0, 4).map(x => `${x.origin}:${x.summary}`).join(' | ')}`)
            const first = v[0]
            const fits = name === 'audio' ? /VO|MULTI|VOSTFR/.test(first.summary) : name === 'maxQuality' ? !first.summary.startsWith('4K') : name === 'origin' ? first.origin === 'Iptv' || !v.some(x => x.origin === 'Iptv') : true
            check(`${name}=${value} change le classement`, fits, first.summary)
            await tool('back')
            await tool('set_preference', { name, value: prefs[name] })
        }
        await tool('set_preference', { name: 'start', value: prefs.start })
        const end = (await state()).preferences
        check('préférences de départ remises', JSON.stringify(end) === JSON.stringify(prefs), JSON.stringify(end))
    },

    async tv() {
        section('Chaînes TV : une seule connexion à la fois')
        await ready()
        await tool('go_to', { section: 'tv' })
        await sleep(3000)
        capture('tv_page')
        const groups = await tool('list_channels')
        check('des groupes sont là', groups.length > 1, groups.slice(0, 5).map(g => g.name).join(', ') + ` (${groups.length})`)
        // Sports or news channels are more likely to have a guide and to be on air: the largest named group is not needed.
        const group = groups.find(g => /france|fr\b/i.test(g.name)) ?? groups[1] ?? groups[0]
        const channels = await tool('list_channels', { group: group.id })
        check('des chaînes dans le groupe', channels.length > 0, `${group.name} : ${channels.length}, ex. ${channels.slice(0, 3).map(c => c.name).join(', ')}`)
        console.log('   programme connu pour', channels.filter(c => c.now).length, 'sur', channels.length)
        const real = channels[0]
        const guide = s => s.live.guide
        await keys_('DOWN')
        const taken = await until(s => guide(s)?.focused, 3000, 100)
        check('le guide prend le focus', !!taken, JSON.stringify(guide(await state())))
        await sleep(2500)
        capture('guide_page')
        console.log('   groupe :', guide(await state()).group, '/ chaîne :', guide(await state()).channel, '/ programme :', guide(await state()).program)
        // Drawer
        await keys_('LEFT')
        await sleep(800)
        const opened = await state()
        check('Gauche sur le programme en cours ouvre le tiroir', guide(opened).drawerOpen)
        capture('guide_drawer')
        await keys_('s', 'p', 'o')
        await sleep(600)
        capture('guide_drawer_filter')
        await keys_('ENTER')
        await sleep(1200)
        const chosen = await state()
        check('le groupe filtré est choisi et le tiroir se ferme', !guide(chosen).drawerOpen && guide(chosen).group !== guide(opened).group, guide(chosen).group)
        await sleep(1200)
        capture('guide_group_chosen')
        await keys_('LEFT')
        await sleep(600)
        await keys_('ESC')
        await sleep(700)
        check('Échap ferme le tiroir sans quitter la page', !guide(await state()).drawerOpen && (await state()).section === 'tv')
        // Moving
        const row0 = guide(await state()).row
        await keys_('DOWN', 'DOWN')
        await sleep(700)
        check('Bas change de chaîne', guide(await state()).row === row0 + 2, guide(await state()).channel)
        const prog0 = guide(await state()).program
        await keys_('RIGHT')
        await sleep(800)
        console.log('   programme suivant :', prog0, '->', guide(await state()).program)
        capture('guide_future')
        // Pinch
        await keys_('PINCH_OUT')
        await sleep(1000)
        check('pincer : panorama', guide(await state()).level === 'Panorama')
        capture('guide_panorama')
        await keys_('PINCH_IN')
        await sleep(1000)
        check('écarter : retour au guide', guide(await state()).level === 'Guide')
        // A very short programme stays readable under the focus.
        await keys_('LEFT')
        await sleep(600)
        await tool('go_to', { section: 'library' })
        await sleep(800)
        await tool('go_to', { section: 'tv' })
        await sleep(3000)
        await keys_('DOWN')
        const pv = await until(s => s.live.previewKey != null && engine(s).playing, 30000)
        if (pv) {
            await sleep(4000)
            capture('tv_preview_frames')
            const silent = audioPeak()
            check('l’aperçu suit le guide et reste muet', silent.active === 0, `${pv.live.previewKey} ${JSON.stringify(silent)}`)
        } else unknown('pas d’aperçu', 'compte plein ou chaîne muette ?')
        // Spreading the fingers: the picture grows until it is the player, on the same stream.
        if (pv) {
            const before = (await state()).player.engine?.session
            await keys_('PINCH_IN')
            await sleep(250)
            capture('guide_zoom_growing')
            const zoomed = await until(s => phase(s) === 'Playing' && s.live.channel != null, 15000)
            check('écarter les doigts passe en plein écran', !!zoomed, zoomed?.live.channel)
            check('sans rouvrir le flux', zoomed && engine(zoomed).session === before, `session ${before}`)
            await sleep(1500)
            capture('guide_zoom_player')
            await keys_('PINCH_OUT')
            const out = await until(s => s.pages.length === 0, 6000)
            check('pincer revient au guide', !!out)
            await sleep(1500)
            capture('guide_back_from_zoom')
            await keys_('DOWN')
            await until(s => s.live.previewKey != null && engine(s).playing, 30000)
        }
        // By its id, not by Enter: a hand on the mouse may have moved the focus in the meantime, which is the app doing its job.
        const shownNow = await state()
        const sessionBefore = shownNow.player.engine?.session
        await tool('play_channel', { group: group.id, id: shownNow.live.previewKey ?? real.id })
        const full = await until(s => phase(s) === 'Playing' && s.live.channel != null, 30000)
        const after = await state()
        check('la chaîne passe en plein écran', !!full, full?.live.channel ?? `${after.pages.join()} ${JSON.stringify(after.player.phase)} ${JSON.stringify(after.live)}`)
        if (full) {
            if (pv) check('sans rouvrir le flux de l’aperçu', engine(full).session === sessionBefore, `session ${sessionBefore}`)
            await sleep(5000)
            capture('tv_full')
            const peak = audioPeak()
            check('avec le son', peak.active > 0, JSON.stringify(peak))
            await sleep(4000)
            const measured = await state()
            const st = measured.stream
            check('le débit réel est mesuré', st.kbps > 0, `${st.kbps} kb/s`)
            check('les images par seconde sont connues', st.fps > 0, `${st.fps} i/s, ${st.width}x${st.height}, ${st.videoCodec}`)
            check('la fréquence du son est connue', st.audioHz > 0, `${st.audioHz} Hz, ${st.audioChannels} canaux, ${st.audioCodec}`)
            check('la santé du flux est jugée', st.health !== 'Measuring', st.health)
            const streams = await tool('channel_streams')
            console.log('   autres flux :', streams.map(x => x.name).join(' | '))
            check('les autres flux de la chaîne sont trouvés', streams.length > 1, streams.length)
            await keys_('ENTER')
            await sleep(900)
            capture('tv_details')
            await keys_('ESC')
            await sleep(300)
            await keys_('LEFT')
            await sleep(900)
            capture('tv_channels_panel')
            await keys_('ESC')
            await sleep(300)
            await keys_('RIGHT')
            await sleep(900)
            capture('tv_programs_panel')
            await keys_('ESC')
            await sleep(300)
            await keys_('SPACE')
            await sleep(1200)
            const held = await state()
            check('Espace met en pause', engine(held).paused, engine(held).paused)
            capture('tv_paused')
            await keys_('SPACE')
            await sleep(1200)
            check('Espace relance', !engine(await state()).paused)
            const before = full.live.channel
            await keys_('UP')
            const zapped = await until(s => phase(s) === 'Playing' && s.live.channel !== before && engine(s).playing, 30000)
            check('Haut change de chaîne', !!zapped, `${before} -> ${zapped?.live.channel}`)
            capture('tv_zap')
            await sleep(3000)
            const zapPeak = audioPeak()
            check('la chaîne suivante a aussi le son', zapPeak.active > 0, JSON.stringify(zapPeak))
        }
        await keys_('ESC')
        const back = await until(s => s.pages.length === 0, 8000)
        check('Échap revient à la liste', !!back)
        await sleep(600)
        capture('tv_back')
        // My List has no trailer: any sound from here would be the channel's.
        await tool('go_to', { section: 'library' })
        const closed = await until(s => s.live.previewKey == null && !engine(s).playing, 12000)
        check('quitter TV ferme le flux', !!closed)
        await sleep(1500)
        const silence = audioPeak()
        check('plus aucun son', silence.active === 0, JSON.stringify(silence))
    },

    /** The buttons of the old player, on a real film. */
    async filmcontrols() {
        section('Boutons du lecteur sur un film')
        await ready()
        await tool('play', INCEPTION)
        const on = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        check('le film joue', !!on, on?.player.current)
        // A clean measure first: nothing touches the film for half a minute.
        await sleep(30000)
        const clean = readFileSync(join(process.env.APPDATA, 'SokoulPC', 'debug.log'), 'utf8').split('\n').filter(l => l.includes('video fps')).slice(-4)
        console.log('   fluidité sans toucher :', clean.map(l => l.slice(l.indexOf('delivered'))).join(' | '), on.player.engine?.video ?? '')
        console.log('   santé :', JSON.stringify((await state()).stream))
        await keys_('SPACE')
        const held = await until(s => engine(s).paused, 3000, 100)
        check('Espace met en pause', !!held)
        capture('film_paused')
        await sleep(9000)
        const still = await state()
        check('les boutons restent affichés en pause', engine(still).paused)
        capture('film_paused_later')
        await keys_('CLICK:' + (process.argv[3] ?? '960:950'))
        await sleep(1200)
        const resumed = await state()
        check('clic sur le bouton Lecture', !engine(resumed).paused, JSON.stringify(engine(resumed).paused))
        const before = engine(resumed).timeMs
        await keys_('ENTER')
        await sleep(800)
        check('Entrée met en pause', engine(await state()).paused)
        await keys_('ENTER')
        await sleep(800)
        await keys_('RIGHT')
        await sleep(1500)
        const moved = await until(s => engine(s).timeMs > before + 9000, 15000)
        check('Droite avance', !!moved, `${before} -> ${moved ? engine(moved).timeMs : '?'}`)
        await sleep(11000)
        const fps = readFileSync(join(process.env.APPDATA, 'SokoulPC', 'debug.log'), 'utf8').split('\n').filter(l => l.includes('video fps')).slice(-2)
        console.log('   fluidité sans pause :', fps.map(l => l.slice(l.indexOf('video'))).join(' | '))
        const m = (await state()).stream
        console.log('   mesures film :', JSON.stringify(m))
        check('les mesures existent aussi sur un film', m.height > 0, `${m.width}x${m.height} ${m.videoCodec}, ${m.kbps} kb/s`)
        await tool('back')
        await clearPlayer()
    },

    /** The anime and manga page. */
    async anime() {
        section('Sources des fans')
        await ready()
        for (const name of ['Naruto', 'Jujutsu Kaisen', 'Demon Slayer', 'One Piece', 'Berserk', 'Frieren']) {
            const d = await tool('anime_check', { name }, 90000)
            console.log('   ', JSON.stringify(d))
        }
        const naruto = await tool('anime_check', { name: 'Naruto' }, 90000)
        check('356 catégories', naruto.categories > 300, naruto.categories)
        check('les musiques et leurs épisodes', naruto.themes > 5, naruto.firstTheme)
        const jjk = await tool('anime_check', { name: 'Jujutsu Kaisen' }, 90000)
        check('épisode vers chapitres', jjk.episode1Chapters.length > 0, jjk.episode1Chapters)
        section('La page, dans la DA des autres')
        await tool('go_to', { section: 'anime' })
        await sleep(9000)
        capture('anime_page')
        await keys_('DOWN', 'DOWN')
        await sleep(2500)
        capture('anime_rows')
        section('Le lecteur de manga')
        await tool('open_manga', { name: 'Demon Slayer' }, 60000)
        await until(s => s.reader?.total > 0, 30000)
        const op = await state()
        check('un chapitre s’ouvre', op.reader?.total > 0, JSON.stringify(op.reader))
        await sleep(3500)
        capture('reader_page')
        check('sens japonais par défaut', op.reader?.rightToLeft === true)
        await keys_('LEFT')
        await sleep(900)
        check('gauche tourne la page en sens japonais', (await state()).reader.page === 1, (await state()).reader.page)
        await keys_('RIGHT')
        await sleep(600)
        check('droite revient', (await state()).reader.page === 0)
        await keys_('m')
        await sleep(600)
        check('M passe en double page', (await state()).reader.mode === 'Double')
        await keys_('LEFT', 'LEFT')
        await sleep(2000)
        capture('reader_double')
        await keys_('m')
        await sleep(2500)
        check('puis en bande continue', (await state()).reader.mode === 'Strip')
        check('à la même page', (await state()).reader.page >= 3, (await state()).reader.page)
        capture('reader_strip')
        await keys_('m')
        await sleep(600)
        await keys_('+', '+')
        await sleep(800)
        check('+ zoome', (await state()).reader.zoom > 1, (await state()).reader.zoom)
        capture('reader_zoom')
        await keys_('0')
        const before = (await state()).reader.chapterIndex
        await keys_('n')
        await sleep(2500)
        check('N va au chapitre suivant', (await state()).reader.chapterIndex === before + 1, (await state()).reader.chapter)
        await keys_('l')
        await sleep(900)
        capture('reader_chapters')
        await keys_('ESC')
        await sleep(400)
        await keys_('ESC')
        await sleep(900)
        check('Échap referme le lecteur', !(await state()).pages.includes('Reader'), (await state()).pages.join())
        section('La fiche fusionnée')
        await tool('go_to', { section: 'series' })
        await sleep(800)
        const hits = await tool('search_titles', { query: 'Demon Slayer' })
        const series = hits.find(h => h.kind === 'tv')
        await tool('open_title', { kind: 'tv', id: series.id })
        await sleep(4000)
        await sleep(5000)
        await keys_('DOWN')
        await sleep(6000)
        capture('title_anime_sections')
        await keys_('DOWN')
        await sleep(4000)
        capture('title_anime_episodes')
        await keys_('DOWN')
        await sleep(3000)
        capture('title_anime_music')
        await keys_('DOWN', 'DOWN')
        await sleep(2500)
        capture('title_anime_music_row')
        await keys_('ENTER')
        const playing = await until(s => s.clip && s.playback?.phase === 'Playing', 25000)
        const clip = await state()
        check('un générique se lance', !!clip.clip, clip.clip)
        await sleep(5000)
        capture('theme_playing')
        for (let i = 0; i < 3 && (await state()).pages.includes('Player'); i++) { await tool('back'); await sleep(1500) }
        check('le lecteur se referme', !(await state()).pages.includes('Player'), (await state()).pages.join())
        check('le titre du générique ne reste pas', !(await state()).clip, (await state()).clip)
        capture('title_after_music')
        section('Le manga dans la fiche')
        for (let i = 0; i < 4 && (await state()).pages.length > 0; i++) { await tool('back'); await sleep(800) }
        await tool('open_title', { kind: 'tv', id: series.id })
        await sleep(5000)
        await keys_('DOWN', 'DOWN', 'DOWN')
        await sleep(2500)
        await keys_('UP')
        await sleep(1000)
        await keys_('RIGHT')
        await sleep(800)
        await keys_('ENTER')
        await sleep(7000)
        capture('title_anime_manga')
        section('Les catégories pour trier')
        for (let i = 0; i < 4 && (await state()).pages.length > 0; i++) { await tool('back'); await sleep(800) }
        check('retour à la page', (await state()).pages.length === 0, (await state()).pages.join())
        await tool('go_to', { section: 'anime' })
        await sleep(4000)
        await keys_('CLICK:1120:60')
        await sleep(1500)
        await keys_('DOWN', 'DOWN', 'DOWN')
        await sleep(2000)
        capture('anime_tools_focus')
        await keys_('ENTER')
        await sleep(8000)
        const cal = await state()
        check('le calendrier s’ouvre', cal.pages.includes('AnimeHub') && cal.anime?.view === 'Calendar', cal.pages.join())
        check('il a des épisodes', cal.anime?.shownInGrid > 0, cal.anime?.shownInGrid)
        capture('anime_calendar')
        await keys_('ESC')
        await sleep(1200)
        check('Échap referme le calendrier', !(await state()).pages.includes('AnimeHub'))
        await keys_('RIGHT', 'ENTER')
        await sleep(7000)
        const cats = await state()
        check('le focus revenu, les catégories s’ouvrent', cats.anime?.view === 'Categories' && cats.anime?.categories > 300, cats.anime?.categories)
        capture('anime_categories')
        await keys_('ENTER')
        await sleep(600)
        await keys_('DOWN', 'DOWN', 'ENTER')
        await sleep(4000)
        const pins = (await state()).anime
        check('deux catégories se combinent', pins?.pinned.split(',').length === 2, pins?.pinned)
        check('et donnent des œuvres', pins?.shownInGrid > 0, pins?.shownInGrid)
        capture('anime_categories_combined')
        await keys_('CLICK:658:178')
        await sleep(4000)
        capture('anime_categories_manga')
        await keys_('ESC')
        await sleep(1000)
        check('Échap revient à la page', !(await state()).pages.includes('AnimeHub'), (await state()).pages.join())
        await tool('forget_reading', { names: 'Berserk, Demon Slayer: Kimetsu no Yaiba' })
        console.log('   fin')
    },

    /** What was added to the guide. */
    async tvplus() {
        section('Guide : ajouts')
        await ready()
        await tool('go_to', { section: 'tv' })
        await sleep(3000)
        const guide = s => s.live.guide
        await keys_('DOWN')
        await until(s => guide(s)?.focused, 3000, 100)
        await sleep(1500)
        console.log('   connexions avant :', JSON.stringify(await tool('iptv_connections')))
        const groups = await tool('list_channels')
        const france = groups.find(g => /france generale/i.test(g.name)) ?? groups[2]
        const list = await tool('list_channels', { group: france.id })
        const tf1 = list.filter(c => /\btf1\b(?! *\+)/i.test(c.name))
        check('une seule ligne par chaîne', tf1.length === 1, tf1.map(c => c.name).join(', '))
        console.log('   chaînes dans le groupe :', list.length, '/ sans guide :', list.filter(c => !c.now).length)
        console.log('   guide court :', JSON.stringify(await tool('short_guide_check', { group: france.id }, 60000)))
        // Search
        await keys_('t', 'f', '1')
        await sleep(1000)
        capture('plus_search')
        const hits = await tool('search_tv', { query: 'tf1' })
        check('la recherche trouve', hits.length > 0, hits.slice(0, 3).map(h => h.program ?? h.channel).join(' | '))
        await keys_('DOWN')
        await sleep(300)
        await keys_('ENTER')
        await sleep(1500)
        const after = await state()
        check('le résultat mène au guide', /tf1/i.test(guide(after).channel ?? ''), guide(after).channel)
        capture('plus_search_result')
        // Jumps
        await keys_('END')
        await sleep(1200)
        capture('plus_tonight')
        const evening = await state()
        console.log('   ce soir :', guide(evening).program)
        // Reminder on a programme to come
        const before = evening.reminders
        await keys_('ENTER')
        await sleep(600)
        const set = await state()
        check('Entrée sur un programme à venir pose un rappel', set.reminders === before + 1 || set.reminders === before - 1, `${before} -> ${set.reminders}`)
        capture('plus_reminder')
        check('et ne lance rien', set.pages.length === 0)
        await keys_('ENTER')
        await sleep(400)
        await keys_('HOME')
        await sleep(1000)
        // Back from the player with the same stream
        const pv = await until(s => s.live.previewKey != null && engine(s).playing, 30000)
        if (pv) {
            await keys_('ENTER')
            const full = await until(s => phase(s) === 'Playing' && s.live.channel != null, 20000)
            const session = full && engine(full).session
            await sleep(2500)
            await keys_('ESC')
            const back = await until(s => s.pages.length === 0, 5000)
            await sleep(1500)
            const kept = await state()
            check('retour au guide sans rouvrir le flux', !!back && engine(kept).session === session && engine(kept).playing, `session ${session} -> ${engine(kept).session}`)
            const quiet = audioPeak()
            check('et sans le son', quiet.active === 0, JSON.stringify(quiet))
            capture('plus_back_on_guide')
        } else unknown('pas d’aperçu')
    },

    /** What was added to the player. */
    async playerplus() {
        section('Lecteur : ajouts')
        await ready()
        await tool('play', INCEPTION)
        const on = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 90000)
        if (!on) {
            const stuck = await state()
            console.log('   bloqué :', JSON.stringify(stuck.player.phase), stuck.player.current, JSON.stringify(stuck.player.engine ?? null).slice(0, 200))
            console.log((await tool('list_versions')).slice(0, 6).map(v => `   ${v.origin} ${v.summary} ${v.readiness} ${v.failure ?? ''}`).join('\n'))
        }
        check('le film joue', !!on, on?.player.current)
        const start = engine(on).volume
        await keys_('+')
        await sleep(200)
        capture('plus_volume')
        check('+ monte le son', engine(await state()).volume === start + 5, engine(await state()).volume)
        await keys_('-')
        await sleep(300)
        await keys_('m')
        await sleep(300)
        check('M coupe le son', engine(await state()).muted)
        await keys_('m')
        await sleep(300)
        await keys_('RIGHT')
        await sleep(200)
        capture('plus_seek_flash')
        await keys_('F11')
        await sleep(1500)
        capture('plus_fullscreen')
        await keys_('F11')
        await sleep(1500)
        capture('plus_after_fullscreen')
        console.log('   ', execFileSync('powershell', ['-NoProfile', '-File', join(here, 'screen-shot.ps1'), '-Name', 'real_after_fullscreen'], { encoding: 'utf8' }).trim())
        check('F11 ne plante pas', (await state()).pages.includes('Player'))
        await keys_('SPACE')
        await sleep(800)
        capture('plus_film_controls')
        await keys_('SPACE')
        await tool('back')
        await clearPlayer()
        // Next episode
        const hits = await tool('search_titles', { query: 'Lupin' })
        const series = hits.find(h => h.kind === 'tv') ?? { kind: 'tv', id: 96677 }
        await tool('play', { kind: 'tv', id: series.id, season: 1, episode: 1 })
        const ep = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0 && engine(s).lengthMs > 0, 90000)
        if (ep) {
            const seconds = Math.round((engine(ep).lengthMs - engine(ep).timeMs) / 1000) - 25
            await tool('seek', { seconds })
            const near = await until(s => engine(s).lengthMs - engine(s).timeMs < 39000, 20000)
            await sleep(1500)
            capture('plus_next_episode')
            check('la fin approche', !!near)
            const startedAt = engine(await state()).session
            const moved = await until(s => engine(s).session !== startedAt && phase(s) === 'Playing' && engine(s).timeMs > 0 && s.player.current != null && s.pages.includes('Player'), 90000)
            check('l’épisode suivant se lance seul', !!moved, moved?.player.current)
            capture('plus_next_started')
        } else unknown('épisode non lancé', JSON.stringify((await state()).player.phase))
        await tool('back')
        await clearPlayer()
    },

    /** How long the provider counts a stream after it closed. */
    async iptvlag() {
        section('Délai du fournisseur après la fermeture d’un flux')
        await ready()
        console.log('   avant :', JSON.stringify(await tool('iptv_connections')))
        const groups = await tool('list_channels')
        const group = groups.find(g => /france/i.test(g.name))
        const channels = await tool('list_channels', { group: group.id })
        for (let round = 0; round < 2; round++) {
            await tool('play_channel', { group: group.id, id: channels[1].id })
            const on = await until(s => phase(s) === 'Playing' && engine(s).playing, 60000)
            console.log('   joue :', !!on, JSON.stringify(on ? null : (await state()).player.phase))
            await sleep(6000)
            console.log('   pendant :', JSON.stringify(await tool('iptv_connections')))
            if (on) check(`tour ${round + 1} : la chaîne joue quel que soit le compteur`, engine(await state()).playing)
            await tool('back')
            const closedAt = Date.now()
            let free = null
            while (Date.now() - closedAt < 30000) {
                const use = await tool('iptv_connections')
                const s = Math.round((Date.now() - closedAt) / 1000)
                console.log(`   +${s} s : ${use.active}/${use.max}`)
                if (use.active < use.max) {
                    free = s
                    break
                }
                await sleep(4000)
            }
            console.log(`   tour ${round + 1} : compteur libre après ${free ?? 'plus de 30'} s`)
        }
    },

    /** What is really on screen while a download advances, not only what the state says. */
    async downloadscreen() {
        section('Le pourcentage à l’écran')
        await ready('upgrade,only')
        await tool('set_preference', { name: 'start', value: 'WaitBest' })
        await tool('play', INCEPTION)
        await until(s => phase(s) === 'Downloading' && s.player.phase.percent >= 25, 30000, 100)
        capture('dlscreen_a')
        const a = await state()
        await until(s => phase(s) === 'Downloading' && s.player.phase.percent >= 75, 30000, 100)
        capture('dlscreen_b')
        const b = await state()
        console.log('   état', a.player.phase.percent, b.player.phase.percent)
        await tool('back')
        await clearPlayer()
        await tool('set_preference', { name: 'start', value: 'ReadyFirst' })
        await tool('play', { ...INCEPTION, choose_first: 'true' })
        await until(s => phase(s) === 'Choosing' && s.player.versions > 0, 30000)
        const list = await tool('list_versions')
        await tool('choose_version', { index: list.find(v => v.readiness === 'NotCached').index })
        await until(s => phase(s) === 'Downloading' && s.player.phase.percent >= 50, 30000, 100)
        capture('dlscreen_panel')
        await tool('back')
        await clearPlayer()
    },

    /** Every summary read by a person, to see that each page is really about the saga. */
    async summaries() {
        const lang = process.argv[3] ?? ''
        await ready('', lang)
        for (const name of ['Star Wars', 'Fist of Fury', 'Cocoon', 'Despicable Me', 'Doom', 'Fast & Furious', 'Jurassic Park', 'X-Men', 'Mission : Impossible', 'The Princess Diaries', 'A Christmas Story', 'Dune', 'Eraser', 'Smile', 'It', 'Saw', 'Bambi', 'Shrek', 'Avatar', 'Machete']) {
            try {
                const p = await tool('saga_info', { name }, 60000)
                console.log(`${name} [${p.overviewSource ?? 'TMDB'}, ${p.overview?.length ?? 0}] ${(p.overview ?? '').slice(0, 150)}`)
            } catch (e) {
                console.log(`${name} : introuvable`)
            }
        }
    },

    async download() {
        section('Vrai téléchargement Real-Debrid')
        await ready()
        const hits = await tool('search_titles', { query: process.argv[3] ?? 'Mickey 17' })
        const candidates = [{ kind: 'movie', id: 550 }, hits[0], { kind: 'movie', id: 680 }, { kind: 'movie', id: 13 }].filter(Boolean)
        let done = false
        for (const c of candidates) {
            await tool('play', { kind: c.kind, id: c.id, choose_first: 'true' })
            await until(s => !s.player.searching && s.player.versions > 0, 40000)
            const list = await tool('list_versions')
            const pick = list.filter(v => v.readiness === 'NotCached' && v.origin === 'Debrid' && (v.seeders ?? 0) >= 5).sort((a, b) => (b.seeders ?? 0) - (a.seeders ?? 0))[0]
            if (!pick) {
                console.log(`   ${c.title ?? c.id} : aucune version à télécharger avec des sources`)
                await tool('back')
                continue
            }
            console.log(`   essai : ${c.title ?? c.id}, ${pick.summary}, ${pick.seeders} sources`)
            await tool('choose_version', { index: pick.index })
            const percents = []
            const end = Date.now() + 480000
            let last
            while (Date.now() < end) {
                last = await state()
                if (phase(last) === 'Downloading') {
                    const p = last.player.phase.percent
                    if (percents[percents.length - 1] !== p) {
                        percents.push(p)
                        console.log(`   ${p} %, ${Math.round(last.player.phase.speedBps / 1e6)} Mo/s, ${last.player.phase.seeders} sources, reste ${last.player.phase.etaSeconds} s`)
                    }
                }
                if (phase(last) === 'Playing' || phase(last) === 'Failed') break
                await sleep(2000)
            }
            capture('download_progress')
            const fell = percents.some((p, i) => i > 0 && p < percents[i - 1])
            check('le pourcentage ne recule jamais', !fell, percents.slice(-6).join(' > '))
            if (percents.length > 1 && percents[percents.length - 1] > percents[0]) {
                check('le pourcentage réel progresse à l’écran', true, percents.join(' > '))
                done = true
            } else {
                unknown(`pas de progression sur cette version`, `${percents.join(',')} puis ${phase(last)} ${last.player.phase.failure ?? ''}`)
            }
            if (phase(last) === 'Playing') {
                const moving = await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 60000)
                check('la version téléchargée se lance seule', !!moving, last.player.current)
                capture('download_then_plays')
            } else unknown('téléchargement pas fini pendant l’essai', phase(last))
            await tool('back')
            if (done) break
        }
        if (!done) unknown('aucune version n’a progressé pendant ces essais')
    },

    async trailers() {
        section('Bandes-annonces lues pour de vrai')
        await ready()
        stopApp()
        await ready()
        const t = await tool('title_info', INCEPTION)
        await tool('open_title', INCEPTION)
        const askedAt = Date.now()
        const auto = await until(s => s.trailer.playing, 60000)
        const took = Math.round((Date.now() - askedAt) / 1000)
        check('la bande-annonce se lance seule sur la fiche', !!auto, auto && `${took} s, ${auto.trailer.key} ${auto.trailer.height}p ${auto.trailer.method}`)
        check('sans attente excessive, même au premier affichage', took <= 25, `${took} s`)
        if (auto) {
            check('c’est la première de la liste, en version originale', auto.trailer.key === t.trailers[0].key || t.trailers.some(v => v.key === auto.trailer.key && v.language === 'en'), `${auto.trailer.key}`)
            check('en haute définition', auto.trailer.height >= 720, auto.trailer.height)
            await sleep(3000)
            const peak = audioPeak()
            check('elle a du son', peak.max > 0.005, JSON.stringify(peak))
            capture('trailer_auto')
        }
        await tool('back')
        const stopped = await until(s => !s.trailer.playing && !engine(s).playing, 10000)
        check('elle s’arrête en quittant', !!stopped)
        await tool('play_trailer', INCEPTION)
        const full = await until(s => phase(s) === 'Playing' && engine(s).playing && engine(s).timeMs > 0, 60000)
        check('lecture plein écran à la demande', !!full)
        await sleep(2000)
        const pk = audioPeak()
        check('avec du son', pk.max > 0.005, JSON.stringify(pk))
        capture('trailer_full')
        await tool('back')
        const gone = await until(s => s.pages.length === 0 && !engine(s).playing, 10000)
        check('le retour arrête tout', !!gone)
        await tool('play_trailer', { ...INCEPTION, index: 1 })
        await tool('back')
        await sleep(15000)
        const late = await state()
        check('une bande-annonce quittée avant de démarrer ne démarre pas', !engine(late).playing, phase(late))
    },

    async input() {
        section('Clavier, souris et pavé tactile')
        await ready()
        await tool('go_to', { section: 'collections' })
        await sleep(5000)
        await keys_('ENTER')
        await sleep(1800)
        const opened = await state()
        check('Entrée : carte réduite', opened.pages.includes('Saga'), opened.pages.join())
        capture('input_reduced')
        await keys_('RIGHT')
        await sleep(1200)
        capture('input_neighbor')
        // PINCH_IN zooms in, fingers apart: it opens. PINCH_OUT zooms out, fingers together: it closes.
        await keys_('LEFT', 'PINCH_IN')
        await sleep(2500)
        capture('input_pinch_open')
        await keys_('PINCH_OUT')
        await sleep(1500)
        capture('input_pinch_reduced')
        await keys_('PINCH_OUT')
        await sleep(1500)
        const closed = await state()
        check('pincer ferme : plein écran, carte, puis retour', closed.pages.length === 0, closed.pages.join())
        await keys_('ENTER')
        await sleep(1800)
        await keys_('CLICK:900:500')
        await sleep(2500)
        capture('input_second_click')
        await keys_('ESC')
        await sleep(800)
        check('Échap revient à Collections', (await state()).pages.length === 0)
        section('Musée : chaque salle, clics')
        await tool('open_saga', { name: 'Star Wars' })
        await sleep(9000)
        capture('museum_entry')
        for (let i = 0; i < 2; i++) await keys_('DOWN')
        await sleep(3000)
        capture('museum_work')
        await keys_('DOWN', 'DOWN')
        await sleep(3000)
        capture('museum_films')
        await keys_('DOWN', 'RIGHT', 'RIGHT')
        await sleep(2000)
        capture('museum_film3')
        await keys_('ENTER')
        await sleep(2500)
        const film = await state()
        check('un film de la salle ouvre sa fiche', film.pages.includes('Title'), film.pages.join())
        await keys_('ESC')
        await sleep(1200)
        await keys_('DOWN')
        await sleep(3000)
        capture('museum_awards')
        await keys_('ENTER')
        await sleep(2500)
        const bar = await state()
        check('une barre de box-office ouvre son film', bar.pages.includes('Title'), bar.pages.join())
        await keys_('ESC')
        await sleep(1200)
        for (let i = 0; i < 12; i++) await keys_('DOWN')
        await sleep(3000)
        capture('museum_faces')
        await keys_('ENTER')
        await sleep(2500)
        const person = await state()
        check('un acteur ouvre sa page', person.pages.includes('Person'), person.pages.join())
        capture('museum_person')
        await keys_('ESC')
        await sleep(1200)
        await keys_('DOWN', 'DOWN')
        await sleep(3000)
        capture('museum_behind')
        // The first trailer of the room, with the mouse: the keys above may have reached the gallery.
        await keys_('CLICK:295:420')
        await sleep(15000)
        const tr = await state()
        check('une bande-annonce de la salle se lance', tr.pages.includes('Player'), `${tr.pages.join()} ${phase(tr)}`)
        await keys_('ESC')
        await sleep(1500)
        check('pas de plantage', !crashed())
        await clearPlayer()
        section('Fiche : boutons et rangées')
        await tool('open_title', { kind: 'movie', id: 11 })
        await sleep(3500)
        await keys_('ENTER')
        await sleep(2000)
        capture('title_full')
        await keys_('RIGHT', 'RIGHT', 'RIGHT', 'ENTER')
        await sleep(1500)
        capture('title_trailers_panel')
        await keys_('ESC')
        await sleep(800)
        await keys_('LEFT', 'ENTER')
        await sleep(1200)
        capture('title_info_modal')
        await keys_('ESC')
        await clearPlayer()
    },

    async soak() {
        section('Mémoire sur une longue navigation')
        await ready()
        await sleep(10000)
        const start = appMemoryMb()
        const samples = [start]
        for (let round = 1; round <= 12; round++) {
            for (const name of ['movies', 'series', 'collections', 'home']) {
                await tool('go_to', { section: name })
                await sleep(1500)
            }
            await tool('open_saga', { name: round % 2 ? 'Star Wars' : 'Toy Story' })
            await sleep(3000)
            await tool('back')
            await tool('open_title', { kind: 'movie', id: [27205, 603, 155, 680][round % 4] })
            await sleep(2500)
            await tool('back')
            if (round % 3 === 0) {
                await tool('play', INCEPTION)
                await until(s => phase(s) === 'Playing' && engine(s).timeMs > 0, 60000)
                await sleep(8000)
                await tool('back')
                await sleep(2000)
            }
            samples.push(appMemoryMb())
            console.log(`   tour ${round} : ${samples[samples.length - 1]} Mo, tas ${(await state()).heapMb} Mo`)
        }
        const firstHalf = samples.slice(4, 8).reduce((a, b) => a + b) / 4
        const lastHalf = samples.slice(9).reduce((a, b) => a + b) / samples.slice(9).length
        check('la mémoire se stabilise', lastHalf < firstHalf * 1.25, `${samples.join(' ')}`)
        check('pas de plantage', !crashed())
        check('l’app répond toujours', !!(await tool('get_state', {}, 5000)))
    },

    async english() {
        section('Système en anglais')
        await ready('', 'en-US')
        await sleep(6000)
        capture('en_home')
        const list = await tool('list_sagas', {}, 120000)
        check('noms anglais', list.curated.some(n => /Star Wars/.test(n)), list.curated.slice(0, 4).join(', '))
        const p = await tool('saga_info', { name: 'Star Wars' }, 120000)
        check('résumé en anglais', /\b(the|is|of)\b/i.test(p.overview ?? ''), (p.overview ?? '').slice(0, 90))
        await tool('play', { ...INCEPTION, choose_first: 'true' })
        await until(s => !s.player.searching && s.player.versions > 0, 40000)
        const v = await tool('list_versions')
        console.log(`   ${v.slice(0, 4).map(x => x.summary).join(' | ')}`)
        check('un système anglais met la VO et le MULTI d’abord', /VO|MULTI/.test(v[0].summary) || !v[0].summary.includes('VF'), v[0].summary)
        capture('en_versions')
        await tool('back')
        await tool('go_to', { section: 'settings' })
        await sleep(1500)
        capture('en_settings')
    },
}

const asked = process.argv.slice(2).filter(a => a in parts)
for (const name of asked) {
    try {
        await parts[name]()
    } catch (e) {
        check(`${name} s’est interrompu`, false, e.message)
    }
    report(join(here, '..', 'renders', 'verify', `${name}.json`))
}
stopApp()
if (existsSync(join(process.env.APPDATA, 'SokoulPC', 'crash.log'))) console.log('crash.log present')
