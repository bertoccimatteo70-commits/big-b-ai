# ITAGPT

Assistente AI locale per Android: chat con memoria persistente, modello incluso nell'APK ed
eseguito sul dispositivo tramite llama.cpp, OCR e importazione documenti, più un ambiente di
sviluppo integrato (**ITAGPT Code**) per lavorare su progetti HTML, Java e C++ senza inviare codice
a servizi esterni.

Nessun account, nessun cloud obbligatorio, nessuna risposta preimpostata: ciò che l'interfaccia
mostra viene dal database locale, dal motore di inferenza o da un controllo deterministico
eseguito dall'app.

> **ITAGPT 2.0.0** mantiene `applicationId com.bigbai.app` **di proposito**: cambiarlo avrebbe
> creato una seconda app e perso conversazioni, progetti e impostazioni già installati. Il nome
> visibile, l'icona, le schermate, la documentazione e gli artifact sono ITAGPT.

---

## Stato verificato di questa release

| Area | Stato | Verifica |
|---|---|---|
| Compilazione debug | COMPLETATO E VERIFICATO | `assembleDebug` eseguito in locale con JDK 17 e SDK 35 |
| Compilazione release con R8 | COMPLETATO E VERIFICATO | `assembleRelease` con minificazione e resource shrinking completato in locale |
| Test unitari JVM | COMPLETATO E VERIFICATO | **222 test, 0 falliti, 0 errori, 0 saltati** nei quattro moduli |
| Modello chat incluso | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | Qwen2.5 1.5B Q4_K_M negli asset, SHA-256 verificato nello script e nell'APK; mai caricato su Android |
| OCR immagini e PDF scansionati | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | ML Kit Latin incluso, OCR/rotazione/20 pagine compilano; nessuna immagine reale elaborata su device |
| Import DOCX/XLSX/CSV/TXT | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | Parser locali coperti da test JVM; picker e rendering non provati su device |
| Voce | PARZIALE | TTS, lingua voce, auto-speak, stop e dettatura di sistema; nessuna conversazione continua |
| Compilazione/esecuzione Java e C++ | BLOCCATO | Android non include `javac`/`clang`; nessun toolchain embedded nella release |
| UI e restyle | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | Compila; nessuno screenshot o test visivo su device |
| Firma di release ufficiale | BLOCCATO | Nessun keystore nei GitHub Secrets; la release è debug-signed ed etichettata |
| Test su dispositivo/emulatore | BLOCCATO | Nessun dispositivo o emulatore disponibile in questo ambiente |
| APK pubblicati da CI | COMPLETATO E VERIFICATO | Debug e release costruiti dalla CI, con checksum, ABI, modelli e firma controllati |

Le voci non verificate **non** sono funzioni dichiarate e nascoste: sono componenti reali che
compilano e hanno test dove possibile, ma che non sono stati eseguiti su Android. La tabella
completa con commit, dati degli APK e motivazioni è in
[docs/RELEASE-2.0.0.md](docs/RELEASE-2.0.0.md).

## APK

Gli APK sono pubblicati negli artifact della run CI di questa release:

- **ITAGPT-Debug-APK** — `ITAGPT-debug-2.0.0.apk`, package `com.bigbai.app.debug`;
- **ITAGPT-Release-APK** — release R8, firmata con la chiave debug e chiamata
  `ITAGPT-release-debug-signed-2.0.0.apk` finché non vengono configurati i Secrets;
- **ITAGPT-CI-Reports** — risultati JUnit e lint.

| APK | Dimensione | SHA-256 | Firma |
|---|---|---|---|
| `ITAGPT-debug-2.0.0.apk` | 1.399.047.913 byte (1334,24 MiB) | `f888d90527a9e35745c153dea342bdddbce8b8d33b69e8890aef19b4f502b44a` | valida, chiave debug |
| `ITAGPT-release-debug-signed-2.0.0.apk` | 1.329.934.753 byte (1268,32 MiB) | `fcef47418a0e04aee5bca942855260b24d73b07f139faa520d102b3f40409fa0` | chiave debug, non ufficiale |

Ogni artifact contiene l'APK, il file `.sha256` e un report `.txt` con dimensione, data, ABI,
modelli e firma. Download: [ITAGPT-Debug-APK](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44933207079/artifacts/14719151937) e
[ITAGPT-Release-APK](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44933207079/artifacts/14719795927)
(retention 30 giorni). La pagina della run e i dati esatti sono in
[docs/RELEASE-2.0.0.md](docs/RELEASE-2.0.0.md).

Entrambi gli artifact sono stati scaricati in questo ambiente: `sha256sum -c` ha confermato i due
checksum, `apksigner verify` la firma debug e `aapt2 dump badging` package e SDK; i due modelli
dentro gli APK hanno lo SHA-256 atteso. Non sono stati installati né avviati su Android.

L'APK è **universale** (arm64-v8a, armeabi-v7a, x86_64), non ci sono split per ABI e non esiste
alcun limite minimo o massimo artificiale di dimensione: lo script di packaging verifica i
componenti reali, non un numero imposto. Include:

- il motore nativo `libbigbai.so` e `libsqlcipher.so` per ogni ABI;
- **Qwen2.5 1.5B Instruct Q4_K_M** (1.117.320.736 byte, SHA-256
  `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`, Apache-2.0);
- l'embedder multilingue `multilingual-e5-small` Q8_0 (132.439.008 byte, MIT).

La chat funziona **subito dopo l'installazione**, senza download, account o API key. Al primo
avvio il modello viene copiato dagli asset all'archivio privato e verificato: servono circa 3 GB
liberi e almeno 4 GB di RAM per il caricamento. Non è un download di rete, ma l'estrazione iniziale
non è istantanea e l'onboarding ne mostra l'avanzamento.

## Novità 2.0.0

- **Modello conversazionale incluso.** Qwen2.5 1.5B Instruct Q4_K_M è dentro l'APK: la prima chat
  non richiede di scaricare un modello. Il 0.5B è stato scartato dopo una valutazione reale perché
  ripeteva frasi e produceva italiano inaffidabile; il report del 1.5B è in
  `docs/ai-eval/`.
- **OCR reale sul dispositivo.** ML Kit Latin Text Recognition legge immagini, screenshot, foto e
  PDF scansionati (fino a 20 pagine), applica la rotazione EXIF, mostra un'anteprima con copia e
  inserimento nel messaggio e segnala immagini senza testo. Italiano e inglese; nessuna immagine
  inviata in rete.
- **Import documenti.** DOCX (paragrafi e tabelle), XLSX (fogli e celle), CSV e TXT vengono letti
  con parser ZIP/XML locali e allegati alla chat. `.doc` e `.xls` binari sono rifiutati con un
  messaggio esplicito.
- **Voce più completa.** Lingua della voce (`auto`/`it`/`en`) e lettura automatica delle nuove
  risposte sono impostazioni persistenti; Stop interrompe anche la sintesi. Resta la dettatura di
  sistema, senza permesso microfono e senza registrazione.
- **Release vera, non solo debug.** R8, minificazione e resource shrinking sono attivi nella
  release, con regole keep per JNI, Room, serializzazione, JGit, PDFBox, SQLCipher e ML Kit. La CI
  produce sia l'APK debug sia quello release; la firma ufficiale è condizionata ai Secrets.
- **Chat e prompt migliorati.** Prompt compatto per i modelli piccoli, sezione OCR/documenti,
  sampler più stabile (temperatura 0,55, top-k 30, top-p 0,90, min-p 0,03, repetition penalty
  1,15, massimo 640 token) e quality gate già presente.
- **CI più verificabile.** Test, lint, build nativa per tre ABI, verifica SHA-256 dei due modelli
  dentro gli APK, `apksigner`, `aapt2`, checksum e report caricati come artifact separati.

## Funzionalità

**Chat**
- Nuova conversazione, invio, stop, modifica, rigenerazione, copia, condivisione, salvataggio come
  file, eliminazione con codice di conferma, rinomina, ricerca, archiviazione, preferiti, cartelle.
- Rendering Markdown, blocchi di codice, tabelle, elenchi; scroll automatico solo quando l'utente è
  in fondo; conservazione di bozza e posizione dopo rotazione o morte del processo.
- Export conversazione in **Markdown**, **TXT** e **PDF**; salvataggio in `Download/ITAGPT`.
- Allegati immagine, PDF, DOCX, XLSX, CSV e TXT; OCR automatico sulle immagini e sui PDF
  scansionati, con anteprima e inserimento del testo.

**AI locale**
- Motore llama.cpp compilato per tre ABI, inferenza CPU con contesto adattivo, token/s e tempo al
  primo token misurati.
- Modello di chat e modello di embedding inclusi nell'APK, verificati con SHA-256.
- Quality gate e controlli deterministici (calcolatrice, controllo italiano) mostrati all'utente.
- Prompt con memoria, documenti, OCR, fonti web opzionali, lessico del tutor e calcoli verificati.
- Modelli più grandi scaricabili dal catalogo con ripresa e verifica SHA-256.

**ITAGPT Code**
- Editor, file explorer, creazione/rinomina/eliminazione, salvataggio, condivisione.
- Analisi statica reale per HTML, CSS, Java e C++ (bilanciamento, regole strutturali, include guard).
- Analisi multi-file, report delle modifiche, generazione di test, agente con strumenti e checkpoint
  Git, sandbox HTML con rete bloccata per default.
- **Non compila né esegue Java/C++**: Android non include `javac`/`clang` e in questa release non è
  stato integrato un toolchain. L'interfaccia non lo dichiara disponibile.

**Voce (parziale)**
- TTS di sistema per leggere una risposta, con stop esplicito e lettura automatica opzionale.
- Dettatura tramite l'activity di riconoscimento vocale del dispositivo; il testo viene inserito
  nella casella e inviato solo dall'utente.
- Lingua voce configurabile; il dispositivo deve avere il pacchetto lingua installato.
- Non sono implementati: conversazione vocale continua, selezione della voce, cronologia vocale.
  L'audio non viene registrato né conservato dall'app.

**Privacy e sicurezza**
- Room + SQLCipher, passphrase casuale protetta da Android Keystore.
- Backup `.bkb` con PBKDF2-HMAC-SHA256 (150.000 iterazioni) e AES-256-GCM.
- Nessuna telemetria, nessun account, nessun upload automatico.
- Rete usata solo per download modelli e ricerca web se abilitata; OCR e chat non chiamano servizi
  esterni. Sandbox HTML senza rete salvo autorizzazione.

**Memoria e lingua**
- Ricordi durevoli con conferma, indice semantico e cancellazione coerente dei vettori.
- Tutor italiano → inglese/spagnolo/francese/tedesco/portoghese; il lessico è in-context
  persistente, non addestramento dei pesi.

## Struttura

```
app/    UI Compose, navigazione, ViewModel, servizi, OCR, voce, export
core/   modelli, Room + SQLCipher, crypto/Keystore, impostazioni, repository, PDF, documenti, backup
llm/    JNI C++ su llama.cpp/mtmd, LlamaEngine, catalogo, modelli inclusi e download
code/   file SAF, analisi statica, patch engine, repository progetti, Git, sandbox HTML
scripts/ fetch-llama.sh, fetch-bundled-model.sh, fetch-bundled-chat.sh, build-native.sh,
         package-debug-apk.sh, package-release-apk.sh
docs/    architettura, build, modelli, limiti, audit, valutazioni e release note
```

Dettagli: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build

Requisiti: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, Gradle 8.11.1.

```bash
bash scripts/fetch-llama.sh          # llama.cpp al commit fissato
bash scripts/fetch-bundled-model.sh  # embedding (132 MB), SHA-256 verificato
bash scripts/fetch-bundled-chat.sh   # chat Qwen2.5 1.5B (1.117 GB), SHA-256 verificato
bash scripts/build-native.sh         # libbigbai.so per arm64-v8a, armeabi-v7a, x86_64

./gradlew :core:testDebugUnitTest :code:testDebugUnitTest :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew assembleDebug
bash scripts/package-debug-apk.sh    # verifica ABI, modelli, firma, manifest, checksum
```

Guida completa, release R8 e firma: [docs/BUILD.md](docs/BUILD.md). Catalogo e valutazione:
[docs/MODELS.md](docs/MODELS.md).

## Limiti noti

Nessun test su dispositivo fisico o emulatore; accelerazione GPU non inclusa; compilatori Java/C++
non disponibili sul dispositivo; API minima **31 (Android 12)**; voce solo TTS/dettatura; il modello
1.5B può inventare fatti e richiede almeno 4 GB di RAM. La release ufficiale non è firmata perché
mancano i Secrets. Elenco completo, con motivazione e alternativa per ogni voce:
[docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## CI

[`.github/workflows/android.yml`](.github/workflows/android.yml) compila la libreria nativa per le
tre ABI, scarica e verifica i due modelli, esegue i 222 test JVM e il lint, costruisce debug e
release R8, verifica firma, manifest, ABI, modelli e checksum, e pubblica:

- **ITAGPT-Debug-APK** — APK + `.sha256` + report `.txt`;
- **ITAGPT-Release-APK** — APK release + `.sha256` + report `.txt`;
- **ITAGPT-CI-Reports** — risultati JUnit e lint.

Il workflow è avviabile manualmente (`workflow_dispatch`) e non impone soglie di dimensione.

## Licenze

Codice ITAGPT: vedere il repository. Componenti: llama.cpp e ggml (MIT), AndroidX e Jetpack
Compose (Apache-2.0), Room (Apache-2.0), SQLCipher (BSD), OkHttp (Apache-2.0), PDFBox-Android
(Apache-2.0), JGit (EPL-2.0), Tinos (Apache-2.0), ML Kit Text Recognition (Google), Qwen2.5 1.5B
Instruct (Apache-2.0), multilingual-e5-small (MIT).
