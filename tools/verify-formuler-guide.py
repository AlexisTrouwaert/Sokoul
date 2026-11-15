"""Guide-only physical TV checks. No provider URLs or credentials are read."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('mode', choices=['groups', 'performance', 'state'])
parser.add_argument('--device', default='192.168.0.132:5555')
parser.add_argument('--label', default='after')
parser.add_argument('--persistent', action='store_true', help='Use the precompiled shell key driver instead of starting input per key')
parser.add_argument('--gap-ms', type=int, default=120, help='Persistent-driver key interval')
args = parser.parse_args()
adb = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe'
out = Path('renders/live')
out.mkdir(parents=True, exist_ok=True)

def run(*command):
    result = subprocess.run([str(adb), '-s', args.device, *command], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
    if result.returncode:
        raise RuntimeError('ADB operation failed')
    return result.stdout

def key(code):
    run('shell', 'input', 'keyevent', str(code))
    time.sleep(.25)

def state():
    text = run('shell', 'am', 'broadcast', '-a', 'com.sokoul.tv.GUIDE_DIAGNOSTICS', '-p', 'com.sokoul.tv')
    payload = text.split('data=', 1)[1].strip()
    if payload.startswith('"'):
        payload = payload[1:-1].replace('\\"', '"')
    data = json.loads(payload)
    return {key: value for key, value in data.items() if key.startswith('guide') or key == 'previewChannelId'}

def shot(name):
    run('shell', 'screencap', '-p', '/sdcard/sokoul-guide-check.png')
    run('pull', '/sdcard/sokoul-guide-check.png', str(out / (name + '.png')))

if args.mode == 'state':
    print(json.dumps(state(), ensure_ascii=True))
elif args.mode == 'groups':
    before = state()
    if not before or before.get('guideDrawer'):
        raise RuntimeError('Start with the guide visible and group drawer closed')
    run('shell', 'input', 'tap', '200', '380')
    time.sleep(.6)
    opened = state()
    if not opened.get('guideDrawer'):
        raise RuntimeError('Group drawer did not open')
    run('shell', 'for k in ' + ' '.join(['20'] * 30) + '; do input keyevent $k; sleep 0.12; done')
    browsed = state()
    shot('guide-groups-browsed-' + args.label)
    stable = ['guideGroup', 'guideChannel', 'guideRow', 'previewChannelId']
    assert all(before[k] == browsed[k] for k in stable), 'Browsing changed applied guide state'
    key(4)
    canceled = state()
    assert not canceled['guideDrawer']
    assert all(before[k] == canceled[k] for k in stable), 'Cancel changed applied guide state'
    run('shell', 'input', 'tap', '200', '380')
    time.sleep(.6)
    key(23)
    same = state()
    assert not same['guideDrawer']
    assert all(before[k] == same[k] for k in stable), 'Active-group confirmation changed guide state'
    (out / ('guide-groups-' + args.label + '.json')).write_text(json.dumps({'before': before, 'opened': opened, 'browsed': browsed, 'canceled': canceled, 'same': same}, indent=2))
    print('Thirty groups, cancel and active-group confirmation preserved channel/time and preview.')
else:
    if not state() or state().get('guideDrawer'):
        raise RuntimeError('Start with the guide visible and group drawer closed')
    results = []
    for trial in range(3):
        before = state()
        if not before.get('guideFocused'):
            raise RuntimeError('The guide must own focus before measuring')
        run('shell', 'dumpsys', 'gfxinfo', 'com.sokoul.tv', 'reset')
        if args.persistent:
            if not 80 <= args.gap_ms <= 1000:
                raise RuntimeError('Use a key interval between 80 and 1000ms')
            injection = run('shell', 'CLASSPATH=/data/local/tmp/sokoul-guide-keydriver.dex app_process /system/bin GuideKeyDriver ' + str(args.gap_ms)).strip()
        else:
            run('shell', 'for k in ' + ' '.join(['20'] * 25 + ['19'] * 25) + '; do input keyevent $k; sleep 0.12; done')
            injection = 'One Android input process per key; requested 120ms sleep plus process/dispatch overhead'
        raw = run('shell', 'dumpsys', 'gfxinfo', 'com.sokoul.tv')
        after = state()
        if (before['guideGroup'], before['guideRow'], True) != (after['guideGroup'], after['guideRow'], after['guideFocused']):
            raise RuntimeError('The measured traversal did not return to its starting guide row')
        results.append({'trial': trial + 1, 'injection': injection, 'start': before, 'end': after,
                        'stats': [line.strip() for line in raw.splitlines() if any(key in line for key in ['Total frames', 'Janky frames', 'percentile'])]})
    (out / ('guide-' + args.label + '-performance.json')).write_text(json.dumps(results, indent=2))
    print('Saved three isolated guide performance samples.')
