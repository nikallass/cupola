# Научно-обоснованный вокальный биофидбек-тренажёр для Android: исследование для техспецификации

## Краткое резюме (TL;DR)
- **Ядро приложения научно состоятельно**: «полётность»/«купол» — это певческая форманта (singer's formant), кластер формант F3–F4–F5, который у мужских голосов даёт пик огибающей спектра около 2.5–3.0 кГц (Bloothooft & Plomp 1986 измеряли её в 1/3-октавной полосе с центром 2.5 кГц для мужчин и 3.16 кГц для женщин; по параметру FHE у теноров центр ≈2705 Гц). Её можно измерять метрикой SPR (Singing Power Ratio, Omori et al. 1996). Но на телефоне абсолютные пороги ненадёжны — всё нужно строить на персональной калибровке и относительном сравнении с собственными записями (именно так и делает референсный сайт sing.suedeai.ai).
- **Главный риск — «наградить не то»**: pressed/зажатый горловой звук и twang тоже дают энергию в районе 3 кГц. Чтобы не поощрять ошибку Никиты (глухой, «надутый» звук), очки за «звон» надо давать только при одновременном выполнении условий: стабильная высота (в центах), высокая периодичность (CPPS/HNR), нормальное вибрато, нормировка по громкости и отсутствие роста spectral tilt / зажатости.
- **Технически всё реализуемо нативно на Kotlin**: `AudioRecord` с источником `UNPROCESSED`/`VOICE_RECOGNITION` (отключает AGC/шумоподавление), FFT через JTransforms/KissFFT, определение высоты через YIN/pYIN/MPM (TarsosDSP) или CREPE-подобную модель on-device, гаптика через `VibrationEffect` с amplitude control. Задержку 20–50 мс даёт Oboe/AAudio.

## Ключевые выводы
1. **Референсный сайт sing.suedeai.ai/analyze** измеряет «ring band» как долю энергии в полосе **2.8–3.2 кГц** от суммарной энергии отображаемого спектра, показывает спектрограмму и метрику «cycle dose» (вокальная нагрузка = pitch × время озвучивания). Сайт прямо предупреждает, что это относительная метрика, зависящая от микрофона и дистанции, и сравнивать надо со своими записями, а не с абсолютным таргетом.
2. **Певческая форманта хорошо задокументирована.** Центральные частоты по типам голоса (Müller et al. 2022, параметр FHE): бас 2384 Гц, баритон 2454 Гц, тенор 2705 Гц, сопрано 3092 Гц. Классический диапазон кластера — 2.5–3.5 кГц.
3. **SPR (Omori 1996)** — основная объективная метрика «звона»: разница (в дБ) между пиком в 2–4 кГц и пиком в 0–2 кГц; коррелирует с воспринимаемым «ringing». Певцы дают достоверно более высокий SPR, чем непевцы.
4. **Вибрато**: норма ≈5.0–6.0 Гц, экстент около ±71 цента (Prame 1997). Медленнее (~2–4 Гц, широкое) = wobble; быстрее (>7–8 Гц, узкое) = tremolo/bleat.
5. **Различение «звона» от зажатости**: pressed phonation даёт более высокий CPP и низкий H1–H2; breathy — низкий CPP, высокий H1–H2, шум между гармониками, крутой спектральный спад. Twang повышает энергию 3–5 кГц через сужение aryepiglottic sphincter — акустически похоже на singer's formant, но отличается по F1/F2 и механизму.
6. **Биофидбек работает, но с оговорками**: визуальная обратная связь помогает новичкам с интонацией, но у продвинутых может ухудшать за счёт когнитивной нагрузки; частая постоянная обратная связь провоцирует «погоню за прибором» (guidance hypothesis) — лучше снижать частоту фидбека для удержания навыка.

## Детали

### 1. Референсный сайт (sing.suedeai.ai/analyze и родительский suedeai.ai)
Сайт «Suede Sing» (продукт Suede Labs AI, автор Jason Colapietro), браузерный, обработка локально, аудио не загружается. Раздел `/analyze` содержит:
- **Спектрограмму** (время по X, частота по Y), с явно отмеченной полосой **2.8–3.2 кГц** («2.8–3.2k marked»).
- **Метрику «Ring band»** — «Tone / Ring band 0.0%»: доля энергии полосы вокруг 3 кГц («золотая колонка» — the singer's formant) от суммарной отображаемой энергии. Прямая цитата: *«The percentage is that band's share of the plotted energy — compare it against your own takes rather than against a target.»*
- **Note** — определение ноты.
- **Vocal load**: «Voiced today» (время озвучивания) и «Cycle dose» (число циклов колебаний связок = pitch × время). Опирается на литературу по вокальной дозиметрии.
- Методология явно **не привязана к конкретной статье**, но терминология корректна (singer's formant, formant tuning у сопрано на высоких нотах, source-filter model).
- Ключевое признание разработчика: *«Every reading here is relative to your microphone, your distance from it, and your room… close to meaningless as an absolute measurement against another singer.»* Это главный вывод для нашего ТЗ — стройте на относительных/калиброванных метриках.
- Совет новичкам прямо на сайте: *«Ignore the ring percentage at first; the spectrogram picture is more useful to a new singer.»*

Родительские разделы: `/studio` (pitch, cents, 8-секундный след), `/warmups` (упражнения со «scoring»), `/range` (тип голоса), `/recorder`. Есть мобильное приложение «Suede Voice for iPhone & Android».

### 2. Научная база

#### 2.1. Певческая форманта (Sundberg)
Определение: устойчивый пик огибающей спектра около 3 кГц у классически поставленных басов, баритонов, теноров, альтов; у сопрано её наличие спорно (на высоких нотах сопрано переходят на formant tuning — настройку R1 на f0, а не на кластеризацию формант). Акустически — кластер F3, F4, F5 (Sundberg с 2003 г. называет «singer's formant cluster», а не одну форманту). Механизм: широкая глотка + суженная эпигортанная трубка (эффект «мундштука», Titze & Story 1997), обеспечивающий импеданс-матчинг между источником и трактом.

**Центральные частоты по типам голоса** (Müller et al. 2022, параметр Frequency of Half Energy, база 1723 образцов):

| Тип | FHE, Гц (±SD) |
|---|---|
| Сопрано | 3092 ± 284 |
| Тенор | 2705 ± 221 |
| Баритон | 2454 ± 206 |
| Бас | 2384 ± 164 |

Отдельно Bloothooft & Plomp 1986 (JASA 79(6):2028–2033) измеряли форманту в 1/3-октавной полосе *«with a center frequency of 2.5 kHz for males and of 3.16 kHz for females»*.

Для **тенора** (голос Никиты) центр ближе к **2.7 кГц**, то есть чуть ниже «канонических 3.2 кГц» — это согласуется с наблюдением сайта («чуть ниже 3.2 кГц») и означает, что для тенора полосу интереса стоит сдвинуть вниз (примерно **2.4–3.2 кГц**).

На спектрограмме форманта выглядит как устойчивая яркая горизонтальная полоса около 3 кГц, сохраняющаяся при смене гласной (руководство VoceVista: *«The singer's formant at 3000 Hz stays strong throughout»*). На LTAS (long-term average spectrum, обычно ≥30 с) — чёткий пик 2–4 кГц у мужских голосов, надёжно отличающий обученных от необученных.

**Зависимости**:
- Форманта усиливается с громкостью **нелинейно**: при росте общего SPL на 10 дБ энергия форманты растёт на **16–19 дБ** (Bloothooft & Plomp 1986, дословно: *«this level increased between 16 and 19 dB for a 10 dB increase in the overall sound level, depending on phonation mode, singer, and vowel»*; PubMed 3722610). Вариация форманты среди мужских голосов мала (~4 дБ), между гласными велика (~16 дБ), от F0 — 9–14 дБ. Это важнейший источник артефакта «громче = больше очков».
- Форманта зависит от гласной (level of singer's formant, LSF, варьирует от −38 дБ на [u] до −7 дБ на [e], Sundberg 2001) → нужна калибровка по гласной.

**У новичков**: форманта появляется постепенно с тренировкой (обычно месяцы–годы). Omori et al. 1996 не нашли разницы между профессиональными и непрофессиональными певцами (подтверждено Radish Kumar et al.: *«no significant difference between professional and nonprofessional male and female singers»*), но чётко отличили певцов от непевцов.

#### 2.2. Количественные метрики
- **SPR (Singing Power Ratio, Omori et al. 1996, J.Voice 10(3):228–235)**: находятся два наивысших пика — в 2–4 кГц и в 0–2 кГц; SPR = разница их амплитуд в дБ. Дословно: *«SPR of sung /a/ in singers was significantly greater than in nonsingers… SPR had a significant relationship with perceptual scores of "ringing" quality.»* **ВАЖНО про знак**: у Omori (2–4 кГц минус 0–2 кГц) число отрицательное, менее отрицательное = больше «звона». У Watts et al. 2006 обратная запись (низ минус верх), число положительное, меньше = лучше. По Watts: «talented» группа ≈ на **8 дБ ниже** «nontalented»; многие nontalented давали **>30 дБ**. Ориентир для обученных (Pillot-Loiseau & Vaissière): singing formant коррелировал с SPR ≈ **−16 дБ** и с разницей «форманта минус спектральный минимум» ≈ 32 дБ, причём именно последний параметр отличал обученных от необученных.
- **Energy Ratio / singer's formant ratio** — варианты того же принципа.
- **Alpha ratio**: отношение суммарной энергии ниже/выше 1000 Гц (в дБ); зависит от subglottal pressure и громкости.
- **Hammarberg index**: разница максимальной энергии в 0–2 кГц и 2–5 кГц (в дБ).
- **Spectral tilt / slope**: наклон спектра; спектр голоса спадает ~6 дБ/октава; более пологий наклон = более «звонкий, несущий» голос.
- **H1–H2**: разница амплитуд 1-й и 2-й гармоник; прокси смыкания связок. Низкий/отрицательный = pressed, высокий = breathy.
- **HNR**: отношение гармоники/шум; выше = чище голос.
- **Jitter/shimmer**: перturbations периода/амплитуды.
- **CPP/CPPS (cepstral peak prominence, smoothed)**: надёжная мера периодичности/качества, не требует pitch-tracking. Здоровый устойчивый гласный ~11–15 дБ (Praat/ADSV; Murton, Hillman & Mehta 2020 дают cutoff 14.45 дБ Praat / 11.46 дБ ADSV для гласного), дисфония — ниже (~4–9 дБ).

Что лучше коррелирует с «ring»: **SPR** (напрямую с воспринимаемым «ringing», Omori 1996); CPPS/HNR — с общей чистотой/качеством голоса.

#### 2.3. Вибрато
Нормы:
- **Rate**: Nix, Perna, James & Allen 2016 (75 студентов-вокалистов, 4 университета, J.Voice 30(6):756.e31) — mean 5.0–5.16 Гц (SD ~0.6–0.7); Glasner & Johnson 2022 (20 профессиональных оперных певцов, J.Voice 36(4):464–478) — mean 5.3 Гц (SD 0.5); Prame 1994 (10 певцов, JASA) — *«the mean rate across singers was 6.0 Hz»*.
- **Extent**: Prame 1997 (Vibrato extent and intonation, JASA 102:616–621) — *«a mean extent of ±71 cents, with a negative correlation to tone duration»*.
- **Wobble**: медленное (~2–4 Гц), широкое. **Tremolo/bleat**: быстрое (>7–8 Гц), узкое.
- **Регулярность** = coefficient of variation длительностей циклов (FM jitter). Растёт с обучением: Mürbe et al. 2007 — SD частоты вибрато на mezzo-forte упала с 0.49 до 0.39 Гц за 3 года обучения.

На спектрограмме вибрато — регулярная рябь во всех гармониках одновременно. **Взаимодействие с pitch-стабильностью**: при измерении «дрейфа» высоты окно усреднения должно быть ≥1 периода вибрато (≥~200 мс), иначе вибрато будет ошибочно засчитано как нестабильность.

#### 2.4. Стабильность высоты и «опора»
Метрики: отклонение в центах от цели, дрейф (drift) во времени, SD высоты, точность интонации. Порог восприятия (voicescience.org, свод рецензируемых данных): ±25 центов ≈ «в тону», >±50 центов — явно слышная фальшь. Медианный абсолютный дрейф в a cappella ~11 центов за ~50 с (Dai & Dixon, «Pitch drift in a cappella choral singing»), значим в 22% записей; медианная ошибка ноты ~19 центов. Sundberg показал, что маскирующий шум ухудшает точность интонации в среднем на 14 центов (роль слуховой vs кинестетической обратной связи).

«Нота улетает / теряет опору» акустически: изменение высоты (обычно понижение при потере опоры или повышение при пережиме), спад амплитуды (RMS), рост breathiness (падение HNR, рост H1–H2), изменение spectral tilt в сторону более крутого.

#### 2.5. Различение «звона» от неправильных механизмов
- **Pressed / зажатый** (ошибка Никиты — глухой «надутый» звук): высокий closed quotient. Акустически pressed даёт **ВЫШЕ CPP и НИЖЕ H1–H2** (сильное смыкание). Это значит, что **CPP сам по себе не отличит pressed от хорошего звона** — различие искать по: интонационной стабильности, свободе вибрато (при зажиме страдает), и по тому, что при истинном звоне энергия 3 кГц растёт при **снижении** дыхательного усилия, а при pressed — за счёт роста давления.
- **Breathy / «продутый»**: если «надутый» звук = много воздуха → низкий CPP/HNR, высокий H1–H2, шум между гармониками, крутой спектральный спад, мало энергии >2 кГц. Это отличить легко (низкий CPP + низкая доля ВЧ).
- **Twang**: сужение aryepiglottic sphincter, усиление энергии 3–5 кГц, но с более высокими F1/F2 и часто назализацией — акустически близко к singer's formant (оба используют epilaryngeal narrowing), различие — в конфигурации тракта.
- **Разминочные упражнения Никиты (SOVT — semi-occluded vocal tract)**: humming, lip trills, tongue trills, «zzz», rolled R. Меняют импеданс тракта, снижают adduction и phonation threshold pressure, дают звук «neither breathy nor pressed» (Titze). На спектре: закрытый рот/hum = сильный низкочастотный резонанс, приглушённые ВЧ; lip/tongue trill = амплитудная модуляция от вибрации губ/языка (видимые пульсации). **Приложение НЕ должно оценивать «звон» во время SOVT** — это отдельный режим/детектор.

#### 2.6. Эффективность биофидбека и геймификации
- **Sing & See / Wilson, Thorpe, Callaghan 2005; Wilson et al. 2008**: реалтайм-визуальная обратная связь помогает новичкам с интонацией; у опытных певцов точность падала из-за дополнительной когнитивной нагрузки. Спектрограмму продвинутые используют активнее, но её труднее интерпретировать (много информации сразу).
- **Обзор MDPI 2022** (Real-Time Visual Feedback in Singing Pedagogy): спектрограммы полезны для register breaks, phonation types, интонации, vowel modification.
- **Motor learning (guidance hypothesis)**: частая постоянная обратная связь провоцирует over-correction («maladaptive short-term corrections») и «погоню за прибором», ухудшая удержание навыка; снижение частоты и summary feedback улучшают retention (Van Stan et al. 2017, ambulatory voice biofeedback — Cohen's d = 4.5 в дни с биофидбеком).
- **Гаптика/геймификация в реабилитации**: биофидбек + геймификация повышают вовлечённость и число повторов (важно для нейропластичности); vibrotactile feedback применяется, например, в реабилитации походки после инсульта.
- Caveats: риск переусердствовать (over-pushing/forcing), награда за громкость, зависимость от гласной.

### 3. Практика измерения на смартфоне

#### 3.1. Ограничения микрофонов
Смартфонные микрофоны часто оптимизированы под речь и фильтруют <200 Гц; в диапазоне ~1–10 кГц отклик обычно пригоден. Современные MEMS-микрофоны имеют довольно плоскую АЧХ. Калибровкой против референса достигается точность ±0.7 дБ для 99.7% измерений (Garg et al., averaging method). **ВЫВОД**: полоса 3 кГц измерима на телефоне, но абсолютные значения ненадёжны из-за АЧХ, дистанции и комнаты → обязательны относительная/калиброванная метрика и персональный baseline.

#### 3.2. Android-специфика
- **AudioRecord** (не MediaRecorder) для сырого PCM в реалтайме.
- **`MediaRecorder.AudioSource.UNPROCESSED`** — сырой сигнал; проверять `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`. Если не поддерживается — **`VOICE_RECOGNITION`** (не применяет AGC/шумоподавление, по документации Android). НЕ включать noise suppression для VOICE_RECOGNITION (нарушает CDD). Замечание из практики: производители не всегда строго следуют спецификации input presets.
- Sample rate 44.1/48 кГц, PCM 16-bit (или float).
- Буфер: `getMinBufferSize()` × 2–4 для запаса от джиттера планировщика; продюсер (read-loop) должен быть неблокирующим (ring buffer).
- Низкая задержка: **Oboe/AAudio** (FAST path, MMAP). VoicePerformance добавлен в Android 10; на более ранних Oboe откатывается на VoiceRecognition.
- Разрешение `RECORD_AUDIO`.

#### 3.3. Реалтайм-DSP
- **FFT-окно**: для ~20–50 мс задержки при 48 кГц — окно 2048 (≈43 мс, разрешение ~23 Гц) или 4096 (≈85 мс, ~12 Гц). Компромисс «разрешение по частоте ↔ задержка». Hop 10–25 мс (overlap 50–75%). Окно Hann/Hamming.
- **A-weighting**: не нужен для метрик отношения полос (работать с raw power или dBFS).
- **Band energy ratio**: сумма power в 2.4–3.2 кГц / сумма в 0.3–2.0 кГц (или вариант SPR по пикам).
- **Pitch detection**: YIN/pYIN/MPM (TarsosDSP), либо CREPE-подобная CNN on-device. По литературе pYIN и CREPE дают ~90–91% raw pitch accuracy на монофоническом вокале (iKala: pYIN 91%, CREPE 90.5%). CREPE точнее на сложных тембрах, но тяжёлый: оригинальный Kim et al. 2018 (ICASSP) — 41 слой, ~22.2 млн параметров, вход 1024 сэмпла @16 кГц, hop 10 мс, 360 бинов. Для телефона практичнее pYIN/MPM или облегчённые модели: **CREPE tiny (~487k параметров, 90.7% RPA), SPICE (~91.4%), PENN, SwiftF0**.
- **Сглаживание score**: attack-release фильтр (быстрый рост, медленный спад) для стабильного индикатора «звона».
- **Спектрограмма**: Jetpack Compose Canvas / custom View / OpenGL ES (для производительности лучше OpenGL или Canvas с bitmap, обновляемым по столбцам).
- **Библиотеки**: TarsosDSP (YIN/MPM/FFT, портирован на Android, есть примеры UtterAsterisk и «Spectrogram in Java»), JTransforms (FFT, чистая Java, используется внутри TarsosDSP), KissFFT/FFTW через NDK, Oboe (аудио I/O).

#### 3.4. Гаптика
- **`VibrationEffect.createOneShot` / `createWaveform`** с амплитудой 1–255. Проверять `Vibrator.hasAmplitudeControl()` — иначе ненулевая амплитуда округляется до 100%.
- **`VibrationEffect.Composition`** с примитивами (`PRIMITIVE_CLICK`, `TICK`, `SLOW_RISE`, `QUICK_RISE`, `QUICK_FALL`, `THUD`) + scale 0..1 и delay. Проверять поддержку примитивов (иначе вся композиция не проиграется вовсе).
- Для **непрерывной вибрации, пропорциональной «звону»**: повторять короткие waveform-сегменты с амплитудой = функцией от score, либо использовать новые envelope-API (`WaveformEnvelopeBuilder` с control points амплитуда/частота/длительность). Рекомендуется начинать и заканчивать waveform на нулевой амплитуде (иначе резонанс/жужжание LRA).
- Требуется `VIBRATE` permission. **Caveat**: поддержка amplitude control и примитивов сильно зависит от устройства; на бюджетных — только on/off, поэтому нужен fallback на pattern-based гаптику.

#### 3.5. Существующие приложения/софт
- **VoceVista** (десктоп): реалтайм спектр + спектрограмма, LTAS, vowel chart, real-time frequency filters, поддержка EGG. Эталон по функциям.
- **Sing & See**: визуальный фидбек, pitch + spectrogram, комбинированный экран, доказательная база (Wilson, Welch, Howard).
- **Praat** (офлайн): золотой стандарт для F0, формант, jitter/shimmer, CPPS.
- **Мобильные тюнеры/мониторы**: Vocal Pitch Monitor, Voice Analyst, SingSharp, Nail the Pitch.
- **Open-source**: TarsosDSP (+ примеры), различные GitHub-спектрограммы/тюнеры, kohnech/android-lowlatency-audio (Oboe demo).

### 4. Рекомендованный набор метрик для приложения

#### 4.1. Формулы и полосы
- **RingRatio (адаптация SPR под тенора)**:
  `RingRatio_dB = 10·log10( E[2.4–3.2 кГц] / E[0.3–2.0 кГц] )`, где E — сумма мощности FFT-бинов. Для тенора центр полосы ~2.7 кГц (сдвиг вниз от 3.2 кГц).
- **PeakSPR (по Omori)**: разница в дБ между максимальным пиком в 2–4 кГц и максимальным пиком в 0–2 кГц.
- **PitchCents**: `1200·log2(f0 / f_target)`; стабильность = SD(cents) по окну ≥200 мс.
- **Drift**: наклон линейной регрессии cents(t) за окно (центы/сек).
- **CPPS** — для гейта периодичности.
- **VibratoRate/Extent**: FFT контура f0 (или автокорреляция); rate (Гц), extent (центы).

#### 4.2. Пороги (относительные/калиброванные)
Абсолютные пороги НЕ использовать. Схема:
1. **Baseline-сессия**: пользователь поёт нейтральный /a/ на комфортной ноте → фиксируем личные baseline RingRatio, CPPS, loudness.
2. Прогресс = превышение над личным baseline (в дБ). «Хорошо» = RingRatio выше baseline на X дБ при выполнении gate-условий.
3. Ориентиры из литературы (для калибровки, НЕ как жёсткие таргеты): SD(cents) < 20–25 центов ≈ «в тону»; вибрато 5.0–6.0 Гц, экстент ~±71 цент; CPPS в верхней части нормы (~14 дБ Praat для гласного); у обученных SPR порядка −16 дБ (по Omori-конвенции).

#### 4.3. Скоринг режима «звон» (gamified)
Очки/вибрация/зелёный фонтан начисляются ТОЛЬКО при **одновременном**:
- **PitchStable**: SD(cents) за 0.5–1 с < порога;
- **Periodicity**: CPPS/HNR выше личного порога (гейт против breathy/продутого);
- **Loudness-normalized**: RingRatio нормирован по громкости (вычесть ожидаемый прирост форманты от SPL — ~1.6–1.9 дБ форманты на 1 дБ SPL по Bloothooft & Plomp — чтобы не давать очки просто за громкость);
- **НЕ в режиме SOVT**.

Score = сглаженная (attack-release) функция от (RingRatio − baseline). Вибрация: амплитуда ∝ score (при `hasAmplitudeControl`), иначе частота импульсов ∝ score.

#### 4.4. Режим «держи верхнюю ноту / опора»
Восходящий паттерн с удержанием верхней ноты. Метрики: Drift (центы/сек), SD(cents), спад амплитуды (наклон RMS), рост breathiness (падение CPPS/HNR). «Опора держится» = |drift| мал, амплитуда не падает, CPPS стабилен. Награда за удержание в коридоре ±X центов в течение N секунд. Учитывать, что вибрато — норма, поэтому оценивать среднюю линию высоты за окно ≥1 периода вибрато.

#### 4.5. Что показывать новичку vs скрывать
- **Показывать новичку**: спектрограмму (визуально «форма звука»), простой индикатор высоты (в тону / нет), «звон-метр» как относительную полоску + геймификацию (монеты/фонтан/вибрация). Совет референсного сайта: новичку игнорировать процент звона, смотреть на форму спектра.
- **Скрывать / выносить в advanced-режим**: числовые SPR/CPPS/H1–H2 в дБ, LTAS, точные центы.

#### 4.6. Риски и митигации

| Риск | Митигация |
|---|---|
| Награда за громкость | Нормировка RingRatio по loudness; вычитать SPL-зависимый прирост форманты (16–19 дБ на 10 дБ SPL) |
| Награда за pressed/зажатость | Гейт по стабильности высоты + свободе вибрато; «звон при меньшем усилии»; НЕ полагаться только на CPP (у pressed он высокий) |
| Награда за breathy/продутость | Гейт по CPPS/HNR (низкий → нет очков), проверка доли ВЧ |
| Артефакты микрофона/АЧХ | Относительные метрики, персональный baseline, фиксированная позиция телефона |
| Зависимость от гласной | Пер-гласная калибровка (/a/, /e/, /i/, /o/, /u/) |
| Twang вместо ring | Отслеживать F1/F2; для классики целить в широкую глотку, не в узкий tract |
| «Погоня за прибором» | Снижать частоту фидбека, давать summary feedback после упражнения |

## Рекомендации
1. **Этап MVP**: спектрограмма (Compose Canvas/OpenGL) + определение высоты (TarsosDSP YIN/MPM) + RingRatio (полоса 2.4–3.2 кГц для тенора) с обязательной baseline-калибровкой. Источник `UNPROCESSED`/`VOICE_RECOGNITION`. Показывать относительную полоску «звона» и индикатор высоты.
2. **Этап 2 (геймификация)**: вибрация с amplitude control ∝ score, «монеты/зелёный фонтан»; очки только при gate-условиях (pitch-стабильность + CPPS/HNR + нормировка по громкости + не-SOVT). Порог — от личного baseline.
3. **Этап 3 (опора)**: режим восходящего паттерна с метриками drift / SD(cents) / amplitude decay / CPPS.
4. **Этап 4**: пер-гласная калибровка, LTAS-режим для продвинутых, экспорт/сравнение записей, история прогресса (аналог «cycle dose» у Suede).

**Бенчмарки для смены решений**:
- Если pYIN даёт октавные ошибки на верхних нотах тенора → перейти на CREPE-tiny/SPICE-подобную on-device модель.
- Если amplitude control не поддержан на большинстве целевых устройств → перейти на pattern-based гаптику.
- Если пользователи «гонятся за метром» (снижается качество при просмотре) → уменьшить частоту визуального фидбека, давать summary после упражнения.
- Если RingRatio растёт вместе с громкостью, а не с качеством → усилить нормировку по SPL и ужесточить CPPS-гейт.

## Оговорки
- Точные средние SPR у Omori 1996 (в дБ) из открытых источников извлечь не удалось (пейволл) — подтверждено лишь «значимо выше у певцов» и определение метрики. Конкретные числа есть у Watts et al. 2006 (talented ≈22.6 дБ, nontalented ≈30.7 дБ, разница ~8 дБ) — но с **обратным знаком** относительно конвенции Omori. Ориентир для обученных (SPR ≈ −16 дБ) — из Pillot-Loiseau & Vaissière.
- CPPS-пороги (11–15 дБ норма) получены на **клинических** популяциях (здоровые vs дисфония), не на «обученные vs необученные певцы» — использовать как ориентир, не как таргет.
- Все абсолютные пороги на телефоне ненадёжны; всё строить на персональном baseline.
- Часть источников по вибрато и терминологии — образовательный сайт voicescience.org (не рецензируемый); числовые значения сверены с рецензируемыми первоисточниками (Nix 2016, Prame 1994/1997, Glasner & Johnson 2022, Mürbe 2007, Bloothooft & Plomp 1986, Omori 1996, Sundberg 2001).
- Разработчик референсного сайта сам подчёркивает относительность метрики звона — это следует зафиксировать и в нашем продукте (сравнение со своими записями, а не с чужими/абсолютными).

## Список источников
- Omori K., Kacker A., Carroll L.M., Riley W.D., Blaugrund S.M. (1996). *Singing power ratio: quantitative evaluation of singing voice quality.* Journal of Voice 10(3):228–235. DOI:10.1016/S0892-1997(96)80003-8. https://pubmed.ncbi.nlm.nih.gov/8865093/
- Watts C., Barnes-Burroughs K., Estis J., Blanton D. (2006). *The Singing Power Ratio as an Objective Measure of Singing Voice Quality in Untrained Talented and Nontalented Singers.* Journal of Voice 20(1). https://personal.utdallas.edu/~assmann/hcs6367/watts_barnes_burroughs_estis_blanton06.pdf
- Sundberg J. (2001). *Level and Center Frequency of the Singer's Formant.* Journal of Voice 15(2):176–186. https://www.sciencedirect.com/science/article/abs/pii/S0892199701000194
- Bloothooft G., Plomp R. (1986). *The sound level of the singer's formant in professional singing.* JASA 79(6):2028–2033. https://pubmed.ncbi.nlm.nih.gov/3722610/ (реф.)
- Müller et al. (2022). *New objective timbre parameters for classification of voice type and fach in professional opera singers.* PMC9605961. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9605961/
- Berndtsson G., Sundberg J. (1994). *Perceptual significance of the center frequency of singer's formant.* KTH QPSR. https://www.speech.kth.se/qpsr/1994/1994_35_4_095-105.pdf
- Frontiers (2025). *Perceived vibrato and the singing power ratio explain overall evaluations in opera singing.* https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2025.1568982/full
- Nix J., Perna N., James K., Allen S. (2016). *Vibrato rate and extent in college music majors: A multicenter study.* Journal of Voice 30(6):756.e31. DOI:10.1016/j.jvoice.2015.09.006
- Prame E. (1994; 1997). *Measurements of the vibrato rate of ten singers* / *Vibrato extent and intonation.* JASA.
- Glasner J.D., Johnson A.M. (2022). *Effects of historical recording technology on vibrato in modern-day opera singers.* Journal of Voice 36(4):464–478.
- Mürbe D., Zahnert T., Kuhlisch E., Sundberg J. (2007). *Effects of Professional Singing Education on Vocal Vibrato.* Journal of Voice.
- Murton O., Hillman R., Mehta D. (2020). *Cepstral Peak Prominence Values for Clinical Voice Evaluation.* AJSLP. https://pubmed.ncbi.nlm.nih.gov/32658592/
- *CPPS and Voice-Source Parameters: Objective Analysis of the Singing Voice.* ScienceDirect S0892199721004331.
- *Estimating Pressed and Breathy Phonation From Cepstral and Spectral Measures.* ScienceDirect S089219972500058X.
- *Vocal Fold Vibratory Kinematics and Acoustic Correlates in Pressed and Breathy Phonation.* PMC13274526.
- Yanagisawa E. et al.; *What is "Twang"?* ScienceDirect S089219970900040X; *Twang Therapy* S0892199705001669.
- Andrade P.A. et al. (2014). *Electroglottographic Study of Seven Semi-Occluded Exercises.* Journal of Voice. https://pubmed.ncbi.nlm.nih.gov/24560003/
- Titze I. (2006). *Voice Training and Therapy with a Semi-occluded Vocal Tract.* JSLHR 49(2):448–459.
- Wilson P., Thorpe W., Callaghan J. (2005/2008). *Looking at singing / Learning to sing in tune: Does real-time visual feedback help?* + https://www.singandsee.com/research-visual-feedback
- *Real-Time Visual Feedback in Singing Pedagogy: Current Trends and Future Directions.* MDPI Appl. Sci. 12(21):10781. https://www.mdpi.com/2076-3417/12/21/10781
- Van Stan J. et al. (2017). *Integration of Motor Learning Principles Into Real-Time Ambulatory Voice Biofeedback.* PMC5533549; *Ambulatory Voice Biofeedback…* PMC5548081.
- Dai J., Dixon S. *Pitch drift in a cappella choral singing.* + *A Longitudinal Study of Intonation in an a cappella Singing Quintet* (Journal of Voice, S0892199718302418).
- Kim J.W., Salamon J., Li P., Bello J.P. (2018). *CREPE: A Convolutional Representation for Pitch Estimation.* ICASSP. arXiv:1802.06182. + SPICE arXiv:1910.11664; SwiftF0 arXiv:2508.18440.
- TarsosDSP (JorenSix). https://github.com/JorenSix/TarsosDSP
- Android Developers: MediaRecorder / Haptics APIs / Custom haptic effects. https://developer.android.com/media/platform/mediarecorder , https://developer.android.com/develop/ui/views/haptics/haptics-apis
- Google Oboe (input presets, low latency). https://github.com/google/oboe
- Garg et al. *An averaging method for accurately calibrating smartphone microphones.* ScienceDirect S0003682X18304468.
- VoceVista User Guide. https://www.vocevista.com/en/doc/introduction/
- Suede Sing — reference tool. https://sing.suedeai.ai/analyze