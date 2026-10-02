"""Read Kotlin's legacy text templates, including nested string interpolation.

This is a maintenance aid, not a runtime translator. New translations are reviewed
in localization_en.json and compiled into Android string resources.
"""
import json
import pathlib
import re


def templates(root):
    found = []

    def scan(s, start=0, stop=False):
        i = start
        while i < len(s):
            if stop and s[i] == '}':
                return i + 1
            if s.startswith('//', i):
                end = s.find('\n', i)
                i = len(s) if end < 0 else end + 1
                continue
            if s.startswith('/*', i):
                end = s.find('*/', i + 2)
                i = len(s) if end < 0 else end + 2
                continue
            if s[i] == '{':
                i = scan(s, i + 1, True)
                continue
            if s[i] == '"':
                triple = s.startswith('"""', i)
                token = '"""' if triple else '"'
                j = i + len(token)
                parts, arg = [], 0
                while j < len(s) and not s.startswith(token, j):
                    if not triple and s[j] == '\\':
                        parts.append(s[j:j + 2])
                        j += 2
                        continue
                    if s.startswith('${', j):
                        j = scan(s, j + 2, True)
                        parts.append('{' + str(arg) + '}')
                        arg += 1
                        continue
                    if s[j] == '$' and j + 1 < len(s) and (s[j + 1].isalpha() or s[j + 1] == '_'):
                        m = re.match(r'\$\w+', s[j:])
                        j += len(m[0])
                        parts.append('{' + str(arg) + '}')
                        arg += 1
                        continue
                    parts.append(s[j])
                    j += 1
                value = ''.join(parts)
                if not triple:
                    value = value.replace('\\n', '\n').replace('\\t', '\t').replace('\\"', '"').replace('\\\\', '\\')
                if re.search('[А-Яа-яЁё]', value) and value not in found:
                    found.append(value)
                i = j + len(token)
                continue
            if s[i] == "'":
                j = i + 1
                while j < len(s):
                    if s[j] == '\\':
                        j += 2
                        continue
                    if s[j] == "'":
                        break
                    j += 1
                i = j + 1
                continue
            i += 1
        return i

    for path in sorted(pathlib.Path(root).rglob('*.kt')):
        scan(path.read_text(encoding='utf-8'))
    return found


if __name__ == '__main__':
    print(json.dumps(templates('app/src/main/java'), ensure_ascii=False, indent=2))
