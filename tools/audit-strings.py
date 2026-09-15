import io, os, re

IOS = re.compile(r'YTLocF?\(@"((?:[^"\\]|\\.)*)"')
AND = re.compile(r'locF?\("((?:[^"\\]|\\.)*)"')


def collect(root, pattern, exts):
    found = set()
    for base, _, files in os.walk(root):
        norm = base.replace(os.sep, '/')
        if '/build/' in norm:
            continue
        for name in files:
            if not name.endswith(exts):
                continue
            try:
                text = io.open(os.path.join(base, name), encoding='utf-8',
                               errors='ignore').read()
            except Exception:
                continue
            for m in pattern.finditer(text):
                found.add(m.group(1))
    return found


ios = collect('youtube-ios/src', IOS, ('.m', '.h'))
andr = collect('Troubadour-Android/app/src/main/java', AND, ('.kt',))

missing = sorted(ios - andr)

print('строк в iOS:', len(ios), '| в Android:', len(andr), '| не перенесено:', len(missing))
print()

for m in missing:
    print(' *', m)
