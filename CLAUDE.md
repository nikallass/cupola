# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Состояние репозитория

Проект **«Купол» (Cupola)**: опенсорсный офлайн Android-визуализатор вокальной тренировки (спектрограмма, нота, обертоны, «звон» = певческая форманта, геймификация с вибрацией). Версия 0.2.0 опубликована на GitHub (эпики E0–E6 в `TICKETS.md`).

- `SPEC.md` — **источник истины**: продуктовая и техническая спецификация, формулы метрик, режимы, модель данных, этапы, тесты. **§15 (решения ревью 2026‑09‑15 и визуал v0.1) имеет приоритет над остальными разделами** там, где они расходятся: там границы версии 0.1, структура экрана «Анализ», палитра, логика сессии/очков, настройки. **§15.6 (раунд 3) — приоритет над §15.5 и §5–6**: без калибровки, купол по доле и возвышению, профиль шума комнаты, трекер высоты.
- `TICKETS.md` — план работ v0.1 по эпикам E0–E5 с зависимостями и критериями готовности; статусы и «Итог …» ведутся прямо в нём. Backlog — в конце файла; не тянуть его в v0.1 без запроса.
- `RESEARCH.md` — исследовательский отчёт (научная база: Sundberg, Omori SPR, CPPS, вибрато, Android-специфика, ссылки на первоисточники).
- `design/mock-analysis-v0.1.html` — согласованный HTML-макет экрана «Анализ»; нативная реализация отличается в деталях, но не в структуре.
- `docs/device-notes.md` — что проверено на стенде (KENSHI E11) и что нет. `CHANGELOG.md` — по версиям.

Общение с владельцем и комментарии в документах — на русском; код, комментарии в коде и commit-сообщения — на английском.

## Стек и цикл сборки

Kotlin 2.1 + Jetpack Compose (BOM 2024.12), AGP 8.7, Gradle 8.11, Android 8.0+ (API 26, target 35), Gradle KTS, **ручной DI (не Hilt)**, DataStore Preferences для настроек и калибровок (Room не подключается в v0.1), собственная radix-2 FFT, YIN за интерфейсом `PitchDetector`. Пакет `ru.dvedev.me.cupola`. Версии — в `gradle/libs.versions.toml`.

Эта машина — arm64-VM (AAPT2 из AGP под неё не поставляется), поэтому сборка идёт на сервере, а запуск — на планшете в локальной сети:

```
scripts/deploy.sh            # rsync → сервер → gradlew :app:assembleDebug → scp APK → adb install + запуск на E11 (~45–60 с)
scripts/deploy.sh --test     # только JVM-тесты :core-dsp/:core-notation на сервере (~10–50 с)
scripts/deploy.sh --check    # gradlew check: тесты + checkNoAndroidImports + lint
scripts/deploy.sh --release  # assembleRelease (подписан debug-ключом) → build/cupola-release.apk
scripts/deploy.sh --test -- --tests "*.PitchTest"   # один класс тестов
scripts/deploy.sh --logcat   # после запуска — logcat процесса приложения
scripts/tap.sh "Старт"       # тап по элементу UI на планшете (uiautomator); scripts/tones.py — тестовые тоны для мониторов
```

- Сервер: `ssh -i ~/.ssh/llms_id_rsa root@217.60.62.102`, проект в `/root/cupola`, JDK 17, SDK в `/opt/android-sdk` (platform 35, build-tools 35). Отчёты тестов — `core-dsp/build/test-results/test/*.xml` там же.
- Планшет: KENSHI E11, `adb connect 192.168.0.16:5555` (Android 13, MT8781, 1200×2000 @ 240 dpi, `VOICE_RECOGNITION` @ 48 кГц — UNPROCESSED не задекларирован; вибромотор без amplitude control). Разрешено автономно ставить/запускать/снимать logcat и скриншоты (`adb exec-out screencap -p`) в пределах пакета `cupola`. Если `adb` показывает `offline` — владелец переподнимает `adb tcpip 5555` по USB. Тестовые сигналы — `aplay` через студийные мониторы, но комната не тихая: результаты «с мониторов» проверять глазами по спектрограмме.
- Логи: тег `CupolaAudio` (источник, `stats:` раз в минуту — кадры/overruns/latency, `session stopped:`).
- Коммиты — автономно и локально. Remote `origin` = https://github.com/nikallass/cupola; push и теги `vX.Y.Z` — только когда владелец просит выпустить/опубликовать. Тег запускает GitHub Actions (`.github/workflows/build.yml`): `check` + debug-APK на каждый push, release-APK + GitHub Release с заметками из `CHANGELOG.md` на тег. Перед тегом: поднять `versionCode`/`versionName` в `app/build.gradle.kts`, раздел в `CHANGELOG.md` с заголовком `## X.Y.Z — дата` (заметки релиза берутся из него), локально `scripts/deploy.sh --check` (lint валит CI). Workflow проверять `build/tools/actionlint` (скачивается скриптом из репозитория actionlint). Подпись release — keystore из секретов `CUPOLA_KEYSTORE*`, иначе debug-ключ.

## Архитектура (модули и ключевые классы)

```
:app            — Compose UI, навигация, ручной DI, сессия, сервис, гаптика, DataStore
:core-audio     — AudioCapture (AudioRecord), AudioEngine (потоки + ring buffer → Analyzer → StateFlow) — Android library
:core-dsp       — FFT, pitch (YIN + HarmonicCombRefiner + PitchTracker), метрики, профиль шума, скоринг, сессия, Analyzer  ← БЕЗ Android-зависимостей (зависит от :core-notation)
:core-notation  — Note/PitchClass, hzToMidi/nearestNote, NoteNames (RU/EN/ruShort/ruFull), центы
:core-testdata  — Signals (sine/sawtooth/harmonicVoice/noise), PitchContour, Wav — синтетика для тестов
```

Ключевая граница: **`:core-dsp`, `:core-notation`, `:core-testdata` — чистый JVM** (`kotlin("jvm")`, JUnit 5 через `kotlin("test")`), тестируются на десктопе и должны остаться пригодными для KMP. Никаких `android.*`/`androidx.*` импортов в них — задача `checkNoAndroidImports` в корневом `build.gradle.kts` валит `check`.

Пайплайн (§4 спеки) в `core-dsp`: `Framer` (2048/480, Hann) → `PowerSpectrum` (dBFS: синус амплитуды 1 → 0 dB) → `YinPitchDetector` → `NoiseFloor` (RMS-минимум + 10‑й перцентиль по бинам; `seed` из `RoomNoise`) → `PitchTracker` (кандидаты `HarmonicCombRefiner` + YIN, непрерывность, удержание при прыжках) → `HarmonicTracker` → `RingMetrics` (доля и возвышение после вычитания шума) → `PitchStats` (медианная линия) → `VibratoAnalyzer` → `Scorer` (гейты, `ScoreParams`/`ScoreWeights`) → `FrameMetrics`. Всё это собирает `Analyzer` (один поток; настройки — `@Volatile var`; `pitchTrace` — отладочный хук). `RoomNoiseSession` и `SessionAccumulator`/`PointsCounter` — там же. Офлайн-трассировка трекера на WAV: `CUPOLA_TRACE_WAV=… ./gradlew :core-dsp:test --tests '*.WavTraceTest'` на сервере (см. `docs/device-notes.md`).

В `:app`:
- `AppGraph` (в `CupolaApp`) — синглтон: `SettingsRepository`, `AudioEngine`, `SessionController`, `HapticsController`; коллектор настроек применяет их к движку (`band`, профиль шума, A4, FFT — с перезапуском); в debug-сборке ставит логкат-трассу трекера (`CupolaTrace`). `startSession/stopSession` — единственная точка старта сессии (сервис + гаптика).
- `analysis/` — `AnalysisViewModel` (сэмплирование метрик 25 Гц для текста, пауза/скролл), `SpectrogramHistory` (60 с × 320 лог-строк, пишется из потока анализа), `SpectrumSnapshot` (двойной буфер), `SessionController` (часы, очки, серия, подсказки).
- `ui/analysis/` — `AnalysisScreen` (портрет/ландшафт), `TopBar`, `NoteZone`, `SpectrogramZone` (кольцевой `Bitmap`, догрузка на каждом кадре дисплея), `SpectrumZone`; подписи на холстах только через `drawLabel` (защита от выхода за границы). `ui/settings/`, `ui/roomnoise/`, `ui/onboarding/`, `ui/theme/` (токены §15.4, `SpectrogramColormap`), `ui/components/Primitives.kt`.
- Навигация — enum `Screen` в `MainActivity` (онбординг → анализ → настройки → шум комнаты); язык — `attachBaseContext` + `recreate()`.
- Строки — `strings.xml` (`values`, `values-en`); подсказки `hint_*`, пояснения настроек `settings_*_help`.

## Инварианты, которые нельзя нарушать

- **Источник аудио**: `UNPROCESSED` только если задекларирован `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` (иначе он ведёт себя как `DEFAULT`), fallback `VOICE_RECOGNITION`. Никогда `MIC`/`DEFAULT`, никогда `NoiseSuppressor`/`AutomaticGainControl` — они убивают полосу ~3 кГц, ради которой всё приложение.
- **Купол не зависит от громкости**: `ring` считается по доле энергии голоса в полосе и по возвышению над соседними участками спектра после вычитания профиля шума комнаты (§15.6). Личной калибровки нет — не возвращать её без запроса владельца; пороги доли/возвышения — только в `ScoreParams`/advanced-настройках.
- **Скоринг не поощряет громкость, зажим и продутость**: `ring` засчитывается при гейтах «голос есть» и «confidence ≥ 0.7» (SOVT — заложен); стабильность высоты влияет только на `steady`. Очки идут от `ring ≥ 0.5`. Точные формулы — §15.6 и §5–6 спеки, не изобретать свои.
- **Стабильность высоты считается по медианной линии** (скользящая медиана 200 мс + среднее 200 мс, окно 500 мс), чтобы вибрато не штрафовалось.
- **Без телеметрии и облака**: никаких Firebase/Analytics/Crashlytics, аудио никуда не уходит.
- **Правило двух слоёв (§7a)**: у каждого понятия певческое имя и акустическое. Приложение **не даёт технических вокальных инструкций** — подсказки описывают, что произошло со звуком, и предлагают только «тише / шаг назад / стоп». Слово «очки», не «монеты».
- **SOVT-режим** (мычание, губная вибрация и т. п.): ring не считается, очки не даются (в v0.1 режим не выбирается, гейт заложен).

## Тесты

Обязательные проверки `:core-dsp` на синтетике из `:core-testdata` перечислены в §10 спеки с конкретными допусками; сквозной набор — `Spec10SuiteTest` через `Analyzer`, бенчмарк там же (цель < 2 мс/кадр; сервер ≈ 1.6). Новый DSP-код — с такими же синтетическими тестами. Имена тестов в бэктиках не должны содержать `[`/`]`.

## Этапы

M1 Анализ (MVP, готов) → M2 Тренировка → M3 Удержание + Диапазон → M4 Продвинутое → M5 Экосистема. Подробно — §11. Не тянуть функции следующих этапов в текущий без запроса.
