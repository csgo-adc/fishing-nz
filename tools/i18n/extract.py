#!/usr/bin/env python3
"""Collect the English text both apps show into tools/i18n/source.json (docs/languages.md).

Every string literal in the Android and iPhone sources is read; Kotlin `$name`/`${…}` and Swift `\\(…)` gaps become
numbered gaps ("Gusts to {0} km/h"), which the apps' translators match against the text they show. Keys, file names,
URLs, patterns, analytics names and the built-in place lists are left out.

Run: python3 tools/i18n/extract.py   (then python3 tools/i18n/check.py to see what each language still needs)
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [
    ('kt', ROOT / 'app/src/main/java'),
    ('swift', ROOT / 'iosApp/CatchCheckNZ'),
    ('swift', ROOT / 'iosApp/TideWidget'),
]
# Built-in data (places, stations, ramps) and server error texts the apps show as they are.
EXTRA = ROOT / 'tools/i18n/extra.json'
OUT = ROOT / 'tools/i18n/source.json'

# The translator itself, whose date words and language names are not English text to translate.
SKIP_FILES = {'Translator.kt', 'Translator.swift', 'I18n.kt', 'I18n.swift', 'LocalizedText.kt', 'LocalizedText.swift'}
# Built-in data rows carry coordinates: their place, station and region names stay as they are.
DATA_ROW = re.compile(r'-\d{2}\.\d+,\s*1[67]\d\.\d+|latitude:|coordinate: \.init')

# Calls whose string arguments are never shown: keys, ids, data rows, patterns, analytics, logging.
SKIP_CALL = re.compile(r'(?:\b(?:getString|optString|optInt|optLong|optDouble|optBoolean|optJSONArray|optJSONObject|getJSONArray|'
    r'getJSONObject|getInt|getLong|getDouble|getBoolean|putString|putInt|putLong|putBoolean|putFloat|putExtra|put|has|isNull|remove|'
    r'contains|startsWith|endsWith|equals|matches|split|replace|removePrefix|removeSuffix|substringBefore|substringAfter|indexOf|'
    r'track|trackScreen|logEvent|Log\.\w|print|println|debugPrint|NSLog|Regex|toRegex|NSRegularExpression|ofPattern|DateTimeFormatter|'
    r'Uri\.parse|URL|URLComponents|URLQueryItem|setRequestProperty|addRequestHeader|setValue|value|header|getHeader|'
    r'getSharedPreferences|forKey|AppStorage|SceneStorage|systemImage|systemName|Image|named|forResource|withExtension|resource|'
    r'getIdentifier|Notification\.Name|setPackage|setAction|setClassName|parse|ZoneId\.of|TimeZone|Locale|identifier|'
    r'FishingSpot|TideStation|BoatRamp|ShoreStructure|WeatherLocation|Coordinate|CLLocationCoordinate2D|LatLng|GeoPoint|'
    r'createNotificationChannel|enqueueUniqueWork|WorkRequest|AnalyticsValue|requireNotNull|check|require|error|assert|precondition|'
    r'fatalError|TODO|decode|encode|CodingKeys|keyPath|jsonObject|JSONObject|text\(|appendQueryParameter|queryItems|'
    r'mimeType|setType|type)\s*\(\s*[^()]*$|\b(?:key|id|tag|route|pattern|channelId|identifier|accessibilityIdentifier|'
    r'testTag|mimeType|type|scheme|host|path|url|file|fileName|event|name)\s*[:=]\s*$|\bcase\s+\w+\s*=\s*$|\.tag\(\s*$)')

def literals(text, lang):
    """Yield (line, key) for each plain string literal, with template gaps replaced by {n}."""
    i, n, line = 0, len(text), 1
    while i < n:
        c = text[i]
        if c == '\n': line += 1; i += 1; continue
        if text.startswith('//', i):
            j = text.find('\n', i); i = n if j < 0 else j; continue
        if text.startswith('/*', i):
            j = text.find('*/', i + 2); seg = text[i:(n if j < 0 else j + 2)]; line += seg.count('\n'); i += len(seg); continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3); seg = text[i:(n if j < 0 else j + 3)]; line += seg.count('\n'); i += len(seg); continue
        if c == "'" and lang == 'kt':
            j = i + 1
            while j < n and text[j] != "'" and text[j] != '\n':
                j += 2 if text[j] == '\\' else 1
            i = j + 1; continue
        if c != '"':
            i += 1; continue
        start, j, parts, gaps, inner = i, i + 1, [], 0, []
        while j < n and text[j] != '"' and text[j] != '\n':
            ch = text[j]
            if lang == 'swift' and text.startswith('\\(', j):
                depth, k = 1, j + 2
                while k < n and depth:
                    if text[k] == '(': depth += 1
                    elif text[k] == ')': depth -= 1
                    elif text[k] == '"':
                        k += 1
                        while k < n and text[k] != '"': k += 2 if text[k] == '\\' else 1
                    k += 1
                inner.append(text[j + 2:k - 1])
                parts.append('{%d}' % gaps); gaps += 1; j = k; continue
            if lang == 'kt' and ch == '$' and j + 1 < n and text[j + 1] == '{':
                depth, k = 1, j + 2
                while k < n and depth:
                    if text[k] == '{': depth += 1
                    elif text[k] == '}': depth -= 1
                    elif text[k] == '"':
                        k += 1
                        while k < n and text[k] != '"': k += 2 if text[k] == '\\' else 1
                    k += 1
                inner.append(text[j + 2:k - 1])
                parts.append('{%d}' % gaps); gaps += 1; j = k; continue
            if lang == 'kt' and ch == '$' and j + 1 < n and (text[j + 1].isalpha() or text[j + 1] == '_'):
                k = j + 1
                while k < n and (text[k].isalnum() or text[k] == '_' or (text[k] == '.' and k + 1 < n and text[k + 1].isalpha())): k += 1
                parts.append('{%d}' % gaps); gaps += 1; j = k; continue
            if ch == '\\' and j + 1 < n:
                esc = text[j + 1]
                parts.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$', "'": "'"}.get(esc, '\\' + esc)); j += 2; continue
            parts.append(ch); j += 1
        line_start = text.rfind('\n', 0, start) + 1
        line_end = text.find('\n', start)
        whole = text[line_start:(n if line_end < 0 else line_end)]
        yield line, ''.join(parts), text[line_start:start], whole
        # Literals inside a gap, such as ${if (boat) "Boat" else "Land"}, fill it when shown, so they are texts too.
        for expression in inner:
            for _, key, before, _ in literals(expression, lang):
                yield line, key, before, whole
        i = j + 1

def keep(key, before):
    s = key
    if SKIP_CALL.search(before): return False
    letters = re.sub(r'\{\d+\}', '', s)
    if sum(ch.isalpha() for ch in letters) < 2: return False
    if re.match(r'^(https?:|market:|itms|mailto:|tel:|geo:|file:|content:|nz\.fishingnz|com\.|android\.|org\.|/|#|\.|@|%[0-9.]*[dfs]$)', s): return False
    if re.fullmatch(r'[a-z0-9_.\-/:%{}\[\]=&?*+^$|\\]+', s): return False      # keys, ids, file names, routes
    if re.fullmatch(r'[A-Z0-9_]+', s): return False                            # CONSTANTS
    if re.fullmatch(r'[a-z]+(?:[A-Z][a-z0-9]*)+', s): return False             # camelCase ids
    if re.fullmatch(r"[EMdyHhmsaKkzZXxLGu'\s,./:\-]+", s): return False        # date patterns
    if re.search(r'\\[dswbDSWB]|\[\^|\(\?|\.\*|\+\?', s): return False         # regular expressions
    if re.fullmatch(r'[A-Z][a-z]+/[A-Za-z_]+', s): return False                # time zones
    if re.fullmatch(r'[A-Za-z]+(-[A-Za-z0-9]+)+', s) and not ' ' in s: return False  # language tags, kebab ids
    if '<' in s and '>' in s and re.search(r'</?[a-z]+', s): return False      # markup
    return True

PRINTF = re.compile(r'%(?:\d+\$)?[-+ 0#]*\d*(?:\.\d+)?(?:ll|l|h)?[dfsi@]')

def normalise(key):
    # printf-style formats ("Wind averages %.0f km/h") are gaps too.
    key = PRINTF.sub('{99}', key).replace('%%', '%').strip()
    for sep in ('· ', ' ·', '·'):
        key = key.strip()
        if key.startswith(sep): key = key[len(sep):]
        if key.endswith(sep): key = key[:-len(sep)]
    key = key.strip(' ,;')
    # Renumber gaps from 0 after trimming.
    order = []
    def renumber(m):
        order.append(m.group(0)); return '{%d}' % (len(order) - 1)
    return re.sub(r'\{\d+\}', renumber, key)

def main():
    found = {}
    for lang, folder in SOURCES:
        for path in sorted(folder.rglob(f'*.{lang}')):
            text = path.read_text(encoding='utf-8')
            if path.name in SKIP_FILES: continue
            for line, key, before, whole in literals(text, lang):
                if DATA_ROW.search(whole) or not keep(key, before): continue
                for piece in key.split(' · '):
                    piece = normalise(piece)
                    if piece and keep(piece, ''):
                        found.setdefault(piece, f'{path.relative_to(ROOT)}:{line}')
    if EXTRA.exists():
        for key in json.loads(EXTRA.read_text(encoding='utf-8')):
            found.setdefault(key, 'tools/i18n/extra.json')
    OUT.write_text(json.dumps(dict(sorted(found.items())), ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    print(f'{len(found)} texts → {OUT.relative_to(ROOT)}')

if __name__ == '__main__':
    main()
