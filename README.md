# Troubadour для Android

Клиент YouTube для Android 4.1 и новее. Это перенос
[Troubadour для iOS](https://github.com/Computershik73/Troubadour): те же
экраны, тот же вид и та же работа с YouTube, только на Kotlin и ExoPlayer.

Проверялся на Android 13 и 5.1.1 (Sony Xperia Z Ultra). На 4.1 и 4.4
проверено только, что приложение запускается.

## Что умеет

Главная, подписки, Shorts, поиск, каналы, плейлисты и миксы, история,
уведомления. Вход по QR-коду и в браузере, несколько каналов одной учётной
записи. Лайки, дизлайки (число от Return YouTube Dislike), подписка,
комментарии, «Сохранить» в плейлист.

Воспроизведение через SABR, прямые трансляции с чатом, субтитры, главы,
SponsorBlock, скачивание роликов, продолжение с того места, где
остановились. Расшифровка `n` и PO-токен считаются на самом устройстве,
без сторонних серверов.

Двенадцать языков интерфейса, светлая и тёмная тема.

Обхода блокировок через WARP, как в iOS-версии, здесь пока нет.

## Сборка

Нужны JDK 8 и Android SDK. Пути задаются в `local.properties`
и `gradle.properties` (`org.gradle.java.home`).

```bash
./gradlew :app:assembleDebug     # отладочная, пишет журнал
./gradlew :app:assembleRelease   # выпускная, без журнала
```

Выпускная сборка подписывается ключом из `keystore.properties` рядом
с проектом:

```
storeFile=troubadour.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Ни ключа, ни этого файла в репозитории нет. Без них собирается
неподписанный APK. Подпись идёт по схемам v1 и v2: v2 нужна новым
Android, а без v1 APK не поставится на Android старше 7.0.

Версии AGP 4.1.3, Gradle 6.8.3, OkHttp 3.12.13 и ExoPlayer 2.13.3 выбраны
намеренно: это последние версии, которые ещё работают с Android 4.1.
Поднимать их нельзя.

Перед выпуском стоит прогнать:

```bash
./gradlew :app:lintDebug          # вызовы, которых нет на Android 4.1
python tools/check-strings.py     # все подписи есть во всех языках
```

## Отличие от iOS-версии

На iOS видео приходится разбирать самим и перекладывать в MPEG-TS, чтобы
его принял AVPlayer. Здесь фрагменты MP4 разбирает сам ExoPlayer,
а при скачивании дорожки сводит `MediaMuxer`. На Android 4.1–4.2
`MediaMuxer` ещё нет, поэтому скачивается готовый поток 360p.

Эфиры играются готовым HLS-плейлистом, а не через SABR.

`РАСШИФРОВКА-N.md` — как устроена расшифровка `n` и что делать, когда
YouTube её снова поменяет. `ОСТАЛОСЬ.md` — что из iOS-версии ещё
не перенесено.

## Спасибо

- [zemonkamin](https://github.com/zemonkamin) за youtube_uwp, с которого
  начиналась iOS-версия;
- [Preloading](https://github.com/Preloading) за
  [TubeReplacer](https://github.com/Preloading/TubeReplacer), откуда
  в iOS-версию пришли основа подачи SABR, описания протокола и получение
  PO-токена. Прямые трансляции, перемотку, смену дорожки и расшифровку `n`
  прямо на устройстве я дописал сам;
- ExoPlayer, OkHttp, Return YouTube Dislike, SponsorBlock.

## Лицензия

GPL-3.0, полный текст в `LICENSE`. У сторонних частей свои лицензии:
ExoPlayer и OkHttp — Apache 2.0, шрифт Roboto — Apache 2.0.

## Автор

Computershik: [4PDA](https://4pda.to/forum/index.php?showuser=4458524),
[Telegram](https://t.me/cmplog),
[поддержать](https://pay.cloudtips.ru/p/83821e32).
