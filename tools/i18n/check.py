#!/usr/bin/env python3
"""Check the language catalogues against tools/i18n/source.json (docs/languages.md).

  python3 tools/i18n/check.py                 report what each language is missing; fail on broken gaps
  python3 tools/i18n/check.py --merge FILE…   add translations from batch files first. A batch is a JSON object
                                              {"English text": ["简体", "繁體", "한국어", "हिन्दी"], …}

Each translation must keep exactly the same {n} gaps as its English text. Texts in tools/i18n/ignore.json stay English
on purpose (URL parts, data headers) and are not reported.
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LANGUAGES = ['zh-Hans', 'zh-Hant', 'ko', 'hi']
SOURCE = ROOT / 'tools/i18n/source.json'
IGNORE = ROOT / 'tools/i18n/ignore.json'
GAP = re.compile(r'\{\d+\}')

def catalogue(language):
    path = ROOT / f'i18n/strings.{language}.json'
    return path, (json.loads(path.read_text(encoding='utf-8')) if path.exists() else {})

def save(path, entries):
    path.write_text(json.dumps(dict(sorted(entries.items())), ensure_ascii=False, indent=1) + '\n', encoding='utf-8')

def main(argv):
    problems = []
    if argv[:1] == ['--merge']:
        batches = [json.loads(Path(f).read_text(encoding='utf-8')) for f in argv[1:]]
        for index, language in enumerate(LANGUAGES):
            path, entries = catalogue(language)
            for batch in batches:
                for source, targets in batch.items():
                    if targets[index]: entries[source] = targets[index]
            save(path, entries)
    source = json.loads(SOURCE.read_text(encoding='utf-8'))
    ignore = set(json.loads(IGNORE.read_text(encoding='utf-8'))) if IGNORE.exists() else set()
    wanted = [key for key in source if key not in ignore]
    for language in LANGUAGES:
        _, entries = catalogue(language)
        for key, value in entries.items():
            if sorted(GAP.findall(key)) != sorted(GAP.findall(value)):
                problems.append(f'{language}: gaps differ: {key!r} → {value!r}')
        missing = [key for key in wanted if key not in entries]
        unused = [key for key in entries if key not in source]
        print(f'{language}: {len(wanted) - len(missing)}/{len(wanted)} translated, {len(missing)} missing, {len(unused)} no longer used')
    for problem in problems: print(problem)
    return 1 if problems else 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
