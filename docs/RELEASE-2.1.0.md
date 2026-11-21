# ITAGPT 2.1.0 — rapporto di consegna

Data: 21 novembre 2026. Commit del codice: `6deb331`. Workflow:
**Android CI - ITAGPT debug and release**. Run:
[#44955362468](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44955362468),
conclusa con successo.

Questo documento distingue ciò che è stato **eseguito e misurato** da ciò che è stato
**implementato ma non provato su dispositivo**. Non contiene numeri inventati: dove una misura non
esiste, è scritto.

## 1. Decisioni di progetto

1. **Nessun modello nell'APK.** Qwen2.5 1.5B e l'embedding sono stati rimossi. Non c'è un modello
   sostitutivo, non c'è un download automatico e non c'è una risposta simulata quando manca il
   modello. La chat mostra un errore chiaro e un pulsante verso la libreria.
2. **Embedding opzionale e separato.** Il codice di retrieval resta; il file si importa dalla
   schermata Impostazioni, viene verificato per dimensione e SHA-256 e non viene mai scaricato.
3. **Ricerca automatica.** L'utente non sceglie provider, endpoint o chiave. Brave e SearXNG sono
   usati solo se già configurati; Google News RSS, DuckDuckGo e Wikipedia coprono il caso senza
   configurazione.
4. **Nessuna accelerazione dichiarata.** `n_gpu_layers = 0` resta: la build non include un backend
   GPU verificato. Il profilo termico riduce il carico, non promette più velocità.
5. **Tablet ≠ telefono ingrandito.** Window size class, drawer permanente e larghezza massima del
   contenuto sono un layout distinto. Mouse, trackpad e tastiera fisica non sono stati provati su
   hardware.

## 2. Modifiche reali al codice

### 2.1 Rimozione dei modelli

| File | Modifica |
|---|---|
| `app/src/main/assets/models/*.gguf` | eliminati (1.117.320.736 + 132.439.008 byte) |
| `llm/src/main/kotlin/com/bigbai/llm/BundledChatModel.kt` | eliminato |
| `llm/src/main/kotlin/com/bigbai/llm/BundledModel.kt` | sostituito da `EmbeddingModelInstaller` + `EmbeddingModelSpec`; nessuna copia da asset |
| `llm/src/main/kotlin/com/bigbai/llm/EmbeddingEngine.kt` | carica solo un file importato e verificato |
| `app/src/main/kotlin/com/bigbai/app/ui/chat/ChatViewModel.kt` | rimosso il percorso “modello incluso”; nessun download automatico |
| `app/src/main/kotlin/com/bigbai/app/ui/onboarding/OnboardingScreen.kt` | nessuna estrazione; modello proposto solo come download esplicito |
| `app/src/main/kotlin/com/bigbai/app/ui/models/ModelManagerViewModel.kt` | rimosso `installBundled` |
| `app/src/main/kotlin/com/bigbai/app/ui/settings/SettingsScreen.kt` | import opzionale `.gguf`, verifica, rimozione |
| `scripts/fetch-bundled-chat.sh`, `scripts/fetch-bundled-model.sh` | eliminati |
| `.github/workflows/android.yml` | rimossi i due step di fetch modelli |
| `scripts/package-debug-apk.sh`, `scripts/package-release-apk.sh` | falliscono se `assets/**/*.gguf` esiste |

L'embedding usa il file `multilingual-e5-small-q8_0.gguf`, 132.439.008 byte, SHA-256
`e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93`, licenza MIT. Il file non è nel
repository né nell'APK.

### 2.2 Ricerca Internet

`core/src/main/kotlin/com/bigbai/core/search/WebSearchClient.kt` è stato riscritto:

- `SearchConfig` con provider `auto|brave|searxng` e finestra temporale.
- Catena automatica con fallback: Brave → SearXNG → Google News RSS (notizie) → DuckDuckGo →
  Wikipedia.
- `Source` esteso con `site`, `publishedAt`, `updatedAt`, `provider`.
- Deduplica per URL canonico (host normalizzato, `utm_*`, `fbclid`, `gclid`, frammenti).
- Lettura delle prime tre pagine con limite 512 KiB, metadati `description`, `og:site_name`,
  `article:published_time`, `article:modified_time`. Google News non viene arricchito perché il
  link è un redirect.
- Limiti di rete espliciti: 10 s connect, 15 s read, 25 s call.
- Parser separati e puri in `SearchParsing`, testati su JVM.

UI:

- pulsante globo nel compositore, colore primario quando attivo, `contentDescription` esplicito;
- riga “N fonti · provider” sotto il campo;
- striscia “Ricerca web in corso…” durante la ricerca;
- pannello fonti con sito, data di pubblicazione, data di aggiornamento e link;
- prompt di sistema che vieta fonti inventate e distingue conoscenza interna da ricerca web.

Verifica dei provider dall'host:

| Provider | Esito | Evidenza |
|---|---|---|
| Google News RSS | **funzionante** | HTTP 200, 102 `<item>` per “intelligenza artificiale”, `pubDate` e `<source>` presenti |
| Wikipedia API (it) | **funzionante** | HTTP 200, risultati con titolo, snippet e timestamp |
| DuckDuckGo HTML | **bloccato dall'IP datacenter** | HTTP 202 anti-bot, 0 risultati; può funzionare su rete mobile, non dichiarato verificato |
| SearXNG pubblico | **bloccato** | `searx.be` HTML invece di JSON, `priv.au` 429, altri challenge anti-bot |
| Brave Search API | **non provato** | richiede una chiave; il percorso è implementato e usato solo se configurato |

Conseguenza onesta: da questo ambiente la ricerca senza chiave è verificata per notizie (Google
News) e conoscenza enciclopedica (Wikipedia); la ricerca web generale dipende da DuckDuckGo, che qui
è stato bloccato. L'app fa fallback e mostra un messaggio se nessun provider risponde, non finge una
ricerca.

### 2.3 Prestazioni e hardware

`core/src/main/kotlin/com/bigbai/core/device/PerformanceAdvisor.kt`:

- legge `PowerManager.currentThermalStatus` (API 29+) e `isPowerSaveMode`;
- riduce thread e contesto in base a stato termico e risparmio energetico;
- restituisce una nota mostrata in chat;
- è usato in `ChatViewModel.ensureModel` e in Diagnostica;
- 5 test JVM coprono i casi normale, severo, critico, battery saver e modalità risparmio.

Non è stata eseguita alcuna misura di TTFT, tok/s, RAM o throttling su Xiaomi Pad 8 o realme 16 Pro:
la differenza di prestazioni osservata dall'utente resta la motivazione del profilo adattivo, non una
prova prodotta qui.

### 2.4 UI, tablet e tastiera

- `MainActivity` legge `calculateWindowSizeClass` dentro la composizione.
- `AppRoot` usa `DismissibleNavigationDrawer` su tablet/foldable e `ModalNavigationDrawer` su
  telefono; il contenuto è limitato a 1200 dp.
- Scorciatoie `Ctrl+N`, `Ctrl+K`, `Ctrl+L`, `Esc` tramite `onPreviewKeyEvent`.
- Stato “Nessun modello installato” con CTA reale in `EmptyChat` e nel compositore.
- Fonti compatte e pannello completo con sito/data.
- Nessuno screenshot o test di rendering è stato prodotto: la resa visiva non è dichiarata
  verificata.

### 2.5 ITAGPT Code

- `CodeAgent.MAX_STEPS` da 5 a 12; tetti invariati: 6 azioni per passo, 40 file per attività,
  200.000 byte per file.
- Il messaggio al limite invita a scrivere «continua», che riparte con la cronologia del progetto.
- Non esiste un terminale Android reale né `javac`/`clang`: nessuna compilazione viene simulata.

## 3. Verifiche eseguite

Comandi eseguiti in questo ambiente:

```bash
./gradlew :core:testDebugUnitTest :llm:testDebugUnitTest :code:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug
```

| Verifica | Esito |
|---|---|
| Compilazione Kotlin/Compose | riuscita |
| Test JVM | **236 test, 0 falliti, 0 errori, 0 saltati** |
| Lint debug | **0 errori, 69 warning** |
| Test parser ricerca | 8 test, inclusi DuckDuckGo HTML e Google News RSS |
| Test `PerformanceAdvisor` | 5 test |
| Test `CodeAgent` | 11 test, incluso il nuovo limite passi |
| Google News RSS da host | HTTP 200, 102 item |
| Wikipedia API da host | HTTP 200 |
| DuckDuckGo da host | HTTP 202 anti-bot (limite dell'ambiente, riportato) |

La CI esegue inoltre build nativa per tre ABI, test, lint, APK debug, APK release R8, verifica
firma/ABI/assenza modelli, checksum e upload artifact.

## 4. APK

| APK | Dimensione | SHA-256 | Firma |
|---|---|---|---|
| `ITAGPT-debug-2.1.0.apk` | 149.551.471 byte (142,62 MiB) | `48845b54a0d867315a02f31fa7a6240c8421ae7424d5a15d70fcb1d348307a8c` | debug |
| `ITAGPT-release-debug-signed-2.1.0.apk` | 80.225.319 byte (76,51 MiB) | `7c08099104a69f40874d4b07349bf0904493d2855e7498e7b9040d41544072e6` | debug, non ufficiale |

Artifact (ZIP, download con accesso GitHub):

- Debug: [ITAGPT-Debug-APK](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44955362468/artifacts/14726864383), 59.410.507 byte;
- Release: [ITAGPT-Release-APK](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44955362468/artifacts/14727970510), 40.286.791 byte;
- Report CI: [ITAGPT-CI-Reports](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44955362468/artifacts/14727546700), 55.906 byte.

La firma è valida ma usa la chiave debug generata dal runner: il certificato può cambiare tra run
e un aggiornamento sopra un'installazione precedente può richiedere la disinstallazione. Non è una
release ufficiale.

Confronto con 2.0.0 (dove l'APK conteneva 1.249.759.744 byte di modelli):

| Voce | 2.0.0 | 2.1.0 |
|---|---|---|
| Modelli negli asset | 1.249.759.744 byte | 0 byte |
| APK debug | 1.399.047.913 byte | 149.551.471 byte |
| APK release | 1.329.934.753 byte | 80.225.319 byte |
| ZIP artifact debug | 1.275.166.483 byte | 59.410.507 byte |
| ZIP artifact release | 1.256.113.456 byte | 40.286.791 byte |

Librerie native non compresse nell'APK release, per ABI: `arm64-v8a` 21,77 MiB, `armeabi-v7a`
14,33 MiB, `x86_64` 23,49 MiB. I file più pesanti sono `libmlkit_google_ocr_pipeline.so`
(6,8–11,6 MB per ABI), `libbigbai.so` (4,2–6,6 MB) e `libsqlcipher.so` (4,0–6,4 MB). La dimensione
dell'app installata non è misurabile senza un dispositivo e non viene stimata. La dimensione
dell'APK non è la dimensione dello ZIP GitHub.

## 5. Matrice finale P0 / P1 / P3

### P0

| Requisito | Stato | Nota |
|---|---|---|
| Nessun modello nell'APK | **COMPLETATO E VERIFICATO** | gate sul pacchetto |
| Qwen rimosso | **COMPLETATO E VERIFICATO** | asset e script eliminati |
| Supporto embedding mantenuto | **IMPLEMENTATO NON VERIFICATO SU DEVICE** | import + SHA, test JVM |
| Nessun modello alternativo | **COMPLETATO** | catalogo solo download esplicito |
| Nessun download automatico | **COMPLETATO** | nessun percorso di rete automatico per i pesi |
| App avviabile senza modelli | **IMPLEMENTATO NON VERIFICATO SU DEVICE** | nessun crash previsto; non eseguita |
| Chat/Code gestiscono l'assenza | **IMPLEMENTATO NON VERIFICATO SU DEVICE** | messaggio chiaro; non eseguito |
| Ricerca intuitiva | **IMPLEMENTATO NON VERIFICATO SU DEVICE** | pulsante globo; non provato |
| Provider automatici | **COMPLETATO E VERIFICATO** | catena + fallback; Google News/Wikipedia reali |
| Fonti e citazioni | **IMPLEMENTATO NON VERIFICATO SU DEVICE** | modello e parsing testati |
| Link funzionanti | **IMPLEMENTATO NON VERIFICATO SU DEVICE** | `ACTION_VIEW`; non aperti su device |
| UI/UX migliorata | **PARZIALE** | modifiche mirate, nessun test visivo |
| Tablet verificati | **PARZIALE** | layout adattivo; nessun tablet |
| Mouse/trackpad verificati | **NON VERIFICATO** | nessuna periferica |
| Tastiere fisiche verificate | **PARZIALE** | scorciatoie implementate; non provate |
| AI Code migliorato | **PARZIALE** | 12 passi; nessun terminale |
| Prestazioni misurate | **PARZIALE** | policy testata; nessun benchmark |
| Stabilità verificata | **PARZIALE** | compilazione e test JVM; nessun runtime |

### P1

| Requisito | Stato |
|---|---|
| Memoria migliorata | **PARZIALE** (retrieval invariato, embedding opzionale) |
| Voce verificata | **PARZIALE** (TTS/dettatura; non su device) |
| OCR verificato | **IMPLEMENTATO NON VERIFICATO SU DEVICE** |
| Documenti verificati | **IMPLEMENTATO NON VERIFICATO SU DEVICE** |
| Localizzazione controllata | **PARZIALE** (non tutte le stringhe sono risorse) |
| Privacy verificata | **PARZIALE** (configurazione; nessun audit runtime) |
| Compatibilità verificata | **PARZIALE** (minSdk 31, 3 ABI compilate; nessun device) |

### P3

| Requisito | Stato |
|---|---|
| Personalizzazione | **PARZIALE** (impostazioni esistenti) |
| Cronologia migliorata | **PARZIALE** (ricerca/archivio esistenti) |
| Produttività migliorata | **PARZIALE** (export/copia/condivisione esistenti) |
| Diagnostica disponibile | **COMPLETATO E VERIFICATO** (compilazione) |
| Onboarding curato | **PARZIALE** (senza modello incluso) |
| Animazioni/interazioni | **PARZIALE** |
| Accessibilità | **PARZIALE** (contentDescription e target; nessun audit) |
| Schermate secondarie | **PARZIALE** (non tutte riviste visivamente) |

## 6. Blocchi residui

1. **Test runtime**: nessun dispositivo fisico o emulatore. Non sono verificati avvio, chat, OCR,
   voce, SAF, Keystore, tablet, mouse/trackpad, rendering.
2. **Firma ufficiale**: `ITAGPT_KEYSTORE_BASE64`, `ITAGPT_KEYSTORE_PASSWORD`, `ITAGPT_KEY_ALIAS`,
   `ITAGPT_KEY_PASSWORD` non sono configurati. La release è firmata con la chiave debug e chiamata
   `release-debug-signed`.
3. **Qualità del modello**: senza modello incluso non è stato possibile eseguire un benchmark 2.1.
   I prompt sono stati rafforzati, ma nessun miglioramento linguistico è dichiarato come misurato.
4. **Ricerca generale senza chiave**: DuckDuckGo è bloccato dall'IP datacenter; da qui sono
   verificati Google News RSS e Wikipedia. Su rete mobile il comportamento può essere diverso.
5. **Compilatori reali**: Android non include `javac`/`clang`; ITAGPT Code non li simula.

## 7. Come installare

1. Scarica l'APK debug dall'artifact `ITAGPT-Debug-APK`.
2. Verifica `sha256sum -c ITAGPT-debug-2.1.0.apk.sha256`.
3. Installa su Android 12+ (minSdk 31). Il pacchetto debug è `com.bigbai.app.debug` e convive con la
   release.
4. All'avvio non c'è alcun modello: apri **Modelli**, scegli quello adatto alla RAM e avvia il
   download. La chat resta utilizzabile per navigare e mostra lo stato reale.
5. Per la ricerca semantica, importa opzionalmente il modello di embedding da Impostazioni.

Nessun APK è stato installato o avviato in questo ambiente: i passaggi 3–5 non sono verificati qui.
