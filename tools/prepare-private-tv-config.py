"""Normalize an owner-supplied configuration into private TEMP inputs, never log values."""
import argparse
import json
import os
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit

ALIASES = {'tmdb': ('tmdb', 'themoviedb', 'themoviedatabase'), 'realdebrid': ('realdebrid',),
           'omdb': ('omdb',), 'fanart': ('fanart',), 'tvdb': ('tvdb',), 'tvmaze': ('tvmaze',),
           'watchmode': ('watchmode',), 'tastedive': ('tastedive',), 'motn': ('motn', 'movieofthenight'),
           'opensubtitles': ('opensubtitles',), 'subdl': ('subdl',)}

def normalize(contents):
    keys, candidates, fields = {}, [], {}
    for line in contents.splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '//')):
            continue
        match = re.match(r'^([^:=]{1,80})\s*[:=]\s*(.*?)\s*$', line)
        if match:
            name = re.sub('[^a-z0-9]', '', match[1].lower())
            value = match[2].strip().strip('"\'')
            provider = next((p for p, aliases in ALIASES.items() if any(a in name for a in aliases)), None)
            if provider and value:
                keys[provider] = value
                continue
            if name in ('username', 'user', 'utilisateur', 'identifiant'):
                fields['username'] = value
            elif name in ('password', 'pass', 'motdepasse'):
                fields['password'] = value
            elif name in ('url', 'server', 'serveur', 'host', 'serverurl', 'urlserveur'):
                fields['server'] = value.rstrip('/')
            elif name in ('epg', 'epgurl', 'xmltv', 'xmltvurl'):
                fields['epgUrl'] = value
            elif name in ('useragent', 'ua'):
                fields['userAgent'] = value
        for address in re.findall(r'https?://[^\s"\'<>]+', line):
            uri = urlsplit(address)
            query = parse_qs(uri.query)
            if query.get('username') and query.get('password'):
                prefix = uri.path.rsplit('/', 1)[0]
                candidates.append({'server': f'{uri.scheme}://{uri.netloc}{prefix}'.rstrip('/'),
                                   'username': query['username'][0], 'password': query['password'][0]})
    if all(fields.get(k) for k in ('server', 'username', 'password')):
        candidates.insert(0, fields)
    unique = []
    for c in candidates:
        identity = tuple(c[k] for k in ('server', 'username', 'password'))
        if not any(tuple(x[k] for k in ('server', 'username', 'password')) == identity for x in unique):
            unique.append(c)
    return keys, unique

def escape(value):
    return ''.join(('\\u%04x' % ord(c)) if ord(c) > 126 else
                   {'\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t', '=': '\\=', ':': '\\:', ' ': '\\ '}.get(c, c)
                   for c in value)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output-directory', required=True, type=Path)
    args = parser.parse_args()
    target = args.output_directory.resolve()
    checkout = Path(__file__).resolve().parent.parent
    if target == checkout or checkout in target.parents:
        parser.error('Private configuration must stay outside the checkout.')
    keys, sources = normalize(args.input.read_text(encoding='utf-8-sig'))
    if not keys.get('tmdb'):
        parser.error('The input has no recognized TMDB credential.')
    target.mkdir(parents=True, exist_ok=True)
    for name, body in [('keys.properties', ''.join('sokoul.key.' + k + '=' + escape(v) + '\n' for k, v in sorted(keys.items()))),
                       ('source-candidates.json', json.dumps(sources))]:
        with os.fdopen(os.open(target / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w', encoding='ascii') as stream:
            stream.write(body)
    print(json.dumps({'providers': sorted(keys), 'source_candidates': len(sources), 'values_hidden': True}))

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError):
        raise SystemExit('Private input could not be prepared. No credential values are included in this error.')
