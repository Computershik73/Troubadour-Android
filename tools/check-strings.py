# -*- coding: utf-8 -*-
"""
Проверка переводов — порт `tools/check-strings.py` из iOS-версии.

Ключом служит русская строка: `loc("Настройки")` ищет в таблице языка
запись с этим ключом. Отсюда две беды, которые видно только проверкой:

  * подпись есть в коде, но её нет в таблице — на чужом языке останется
    русский текст, и заметит это только тот, кто на этом языке смотрит;
  * подпись есть в одной таблице и нет в другой — то же самое, но лишь
    для части языков.

Ещё сверяются подстановки: `%@`, `%ld` и прочие должны совпадать
с русскими по составу. Перевод, где подстановку потеряли или добавили
лишнюю, роняет форматирование на месте — а найти это в готовом
приложении можно только случайно.

Запуск из корня проекта:

    python tools/check-strings.py
"""

import io
import json
import os
import re
import sys

CODE = 'app/src/main/java'
LANG = 'app/src/main/assets/lang'

# `loc("…")` и `locF("…", …)` — с учётом экранированных кавычек внутри.
CALL = re.compile(r'\blocF?\("((?:[^"\\]|\\.)*)"')

# Подписи, приходящие в `loc()` переменной, отсюда не видны.
#
# У списков надпись «когда пусто» задаётся полем, а переводится уже при
# показе: одной надписи на все списки не хватает — у поиска «ничего
# не нашлось», у канала «у канала пока нет роликов». Такие места
# помечаются рядом с присваиванием: `// подпись: "…"`.
MARKED = re.compile(r'//\s*подпись:\s*"((?:[^"\\]|\\.)*)"')

# Подстановки: `%@`, `%ld`, `%lu`, `%.2f`, `%%` и прочие.
#
# Номер позиции (`%1$@`) учитывается и отбрасывается: в языках с иным
# порядком слов переводчик вправе переставить подстановки местами, и это
# не расхождение, а единственный способ перевести. Сравнивать надо род
# подстановок, а не их место.
FORMAT = re.compile(r'%(?:(\d+)\$)?([-#0-9.+ ]*(?:l{0,2})[@a-zA-Z%])')


def formats(text):
    """Роды подстановок в строке, по возрастанию, без номеров позиций."""
    return sorted(match.group(2) for match in FORMAT.finditer(text))


def used_in_code():
    """Все ключи, которые код действительно спрашивает."""
    found = {}

    for base, _, files in os.walk(CODE):
        for name in files:
            if not name.endswith('.kt'):
                continue

            path = os.path.join(base, name)
            text = io.open(path, encoding='utf-8').read()

            for pattern in (CALL, MARKED):
                for match in pattern.finditer(text):
                    key = match.group(1).replace('\\"', '"').replace('\\\\', '\\')

                    found.setdefault(key, path)

    return found


def tables():
    """Таблицы по языкам: имя языка -> разобранный словарь."""
    result = {}

    for name in sorted(os.listdir(LANG)):
        if not name.endswith('.json'):
            continue

        path = os.path.join(LANG, name)

        try:
            result[name[:-5]] = json.loads(io.open(path, encoding='utf-8').read())
        except ValueError as error:
            print('!! %s не разобрался: %s' % (name, error))

            result[name[:-5]] = None

    return result


def main():
    keys = used_in_code()
    langs = tables()

    if not langs:
        print('!! таблиц переводов не нашлось в %s' % LANG)

        return 1

    broken = [name for name, table in langs.items() if table is None]

    if broken:
        return 1

    bad = 0

    # 1. Ключи, которых нет в таблице.
    for name, table in sorted(langs.items()):
        missing = [key for key in keys if key not in table]

        if missing:
            bad += len(missing)

            print('%s: нет %d подписей' % (name, len(missing)))

            for key in sorted(missing)[:20]:
                print('    %s' % key)

            if len(missing) > 20:
                print('    … и ещё %d' % (len(missing) - 20))

    # 2. Подстановки должны совпадать с русскими.
    for name, table in sorted(langs.items()):
        for key, value in sorted(table.items()):
            if not isinstance(value, str):
                continue

            here = formats(key)
            there = formats(value)

            if here != there:
                bad += 1

                print('%s: подстановки разошлись' % name)
                print('    ключ:   %s  %s' % (key, here))
                print('    перевод: %s  %s' % (value, there))

    # 3. Записи, которых код не спрашивает, — не ошибка, но стоит знать.
    extra = set()

    for table in langs.values():
        extra |= set(table.keys()) - set(keys.keys())

    print()
    print('подписей в коде: %d, языков: %d' % (len(keys), len(langs)))

    if extra:
        print('в таблицах есть %d записей, которых код не спрашивает' % len(extra))

    if bad:
        print('расхождений: %d' % bad)

        return 1

    print('расхождений нет')

    return 0


if __name__ == '__main__':
    sys.exit(main())
