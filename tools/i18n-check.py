"""Compares a catalogue with the source: keys it holds that the interface does not have, and texts it lacks.

Run from the repository root: python tools/i18n-check.py es [de nl ...]
Also checks that every value placeholder of a key ({1}, %s, %d, %1$s...) is kept in its translation.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / 'app/src/main/resources/i18n'
PLACEHOLDERS = re.compile(r'\{\d+\}|%\d+\$[sd]|%[sd]|%%')


def read(path: Path) -> dict:
    out = {}
    for line in path.read_text(encoding='utf8').splitlines():
        if not line or line.startswith('#') or '\t' not in line:
            continue
        key, value = line.split('\t', 1)
        out[key] = value
    return out


def main(codes) -> int:
    source = read(DIR / 'source.tsv')
    failed = False
    for code in codes:
        catalogue = read(DIR / f'{code}.tsv')
        unknown = [k for k in catalogue if k not in source]
        missing = [k for k in source if k not in catalogue]
        broken = [k for k, v in catalogue.items() if sorted(PLACEHOLDERS.findall(k)) != sorted(PLACEHOLDERS.findall(v))]
        print(f'{code}: {len(catalogue)} of {len(source)} | unknown keys {len(unknown)} | missing {len(missing)} | placeholder mismatch {len(broken)}')
        for k in unknown[:10]:
            print('  unknown:', repr(k))
        for k in missing[:10]:
            print('  missing:', repr(k))
        for k in broken[:10]:
            print('  placeholders:', repr(k), '->', repr(catalogue[k]))
        failed = failed or bool(unknown or broken)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
