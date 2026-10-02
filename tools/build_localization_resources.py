"""Validate reviewed translations and print an apply_patch patch; no file writes."""
import json
import pathlib
import re
from xml.sax.saxutils import escape

root = pathlib.Path(__file__).resolve().parent
ru = json.loads((root / 'localization_ru.json').read_text(encoding='utf-8'))
en = json.loads((root / 'localization_en.json').read_text(encoding='utf-8'))
active_indices = [i for i, value in enumerate(ru) if value is not None]
# Deleted labels leave an empty slot so existing resource IDs remain stable.
assert set(en) == {str(i) for i in active_indices}
for i, value in enumerate(ru):
    if value is None:
        continue
    assert sorted(re.findall(r'\{\d+}', value)) == sorted(re.findall(r'\{\d+}', en[str(i)])), i
    assert not re.search('[А-Яа-яЁё]', en[str(i)]), i


def text(value):
    value = value.replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'").replace('\n', '\\n').replace('\t', '\\t')
    return '&quot;' + escape(value) + '&quot;'


def strings(language):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for i in active_indices:
        value = ru[i] if language == 'ru' else en[str(i)]
        lines.append(f'    <string name="loc_{i:03d}" formatted="false">{text(value)}</string>')
    return '\n'.join(lines + ['</resources>'])


# Geographic suggestions are data, not app labels. Preserve their original names.
indices = [i for i in active_indices if i not in range(520, 528)]
arrays = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>',
          '    <string-array name="legacy_text_templates" translatable="false">']
arrays.extend(f'        <item>{text(ru[i])}</item>' for i in indices)
arrays.extend(['    </string-array>', '    <string-array name="legacy_text_keys" translatable="false">'])
arrays.extend(f'        <item>loc_{i:03d}</item>' for i in indices)
arrays.extend(['    </string-array>', '</resources>'])

project = root.parent.as_posix()
files = {
    'app/src/main/res/values/strings.xml': strings('en'),
    'app/src/main/res/values-ru/strings.xml': strings('ru'),
    'app/src/main/res/values/localization_catalog.xml': '\n'.join(arrays),
}
print('*** Begin Patch')
for name, content in files.items():
    print(f'*** Add File: {project}/{name}')
    for line in content.splitlines():
        print('+' + line)
print('*** End Patch')
