# ITAGPT

Assistente AI locale per Android: chat con memoria persistente, modelli GGUF eseguiti sul
dispositivo tramite llama.cpp e un ambiente di sviluppo integrato (**ITAGPT Code**) per lavorare su
progetti HTML, Java e C++ senza inviare codice a servizi esterni.

Nessun account, nessun cloud obbligatorio, nessuna risposta preimpostata: ciò che l'interfaccia
mostra viene dal database locale, dal motore di inferenza o da un controllo deterministico
eseguito dall'app.

> **ITAGPT 2.0.0** è l'aggiornamento di branding e prodotto di BIG B AI 1.5.1. L'`applicationId`
> resta `com.bigbai.app` **di proposito**: cambiarlo avrebbe creato una seconda app e perso
> conversazioni, progetti e impostazioni già installati. Il nome visibile, l'icona, le schermate,
> la documentazione e gli artifact sono ITAGPT.

---

## Stato verificato di questa release

| Area | Stato | Verifica |
|---|---|---|
| Compilazione debug | ✅ verde | `assembleDebug` eseguito in locale (JDK 17, SDK 35) |
| Test unitari JVM | ✅ **214 test, 0 falliti** | `:core`, `:code`, `:llm`, `:app`; report JUnit in CI |
| Rebranding ITAGPT | ✅ completo | nome, icona adattiva, temi, stringhe, drawer, About, README, docs, script, CI |
| Chat: modifica e rigenerazione | ✅ implementate | compilano; regressione non eseguita su dispositivo |
| Chat: export Markdown/TXT/PDF | ✅ implementati | Markdown/TXT coperti da test JVM; PDF generato con `PdfDocument` (solo compilazione) |
| Voce: TTS e dettatura | 🟡 parziale | TTS di sistema e recognizer di sistema; nessun test su device |
| Dimensione testo | ✅ impostazione reale | scala applicata a tutta la UI con `LocalDensity` |
| Lint Android | ✅ 0 errori, 66 warning | `:app:lintDebug` eseguito in locale e in CI |
| APK da GitHub Actions | ✅ verificato | artifact scaricato, `sha256sum -c` OK, firma v2 valida, ABI e modello presenti; run [44922648963](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44922648963) |
| Inferenza su dispositivo reale | ⛔ non verificata | nessun dispositivo/emulatore disponibile |
| OCR, DOCX/XLSX/CSV, compilatori Java/C++ | ⛔ non implementati | motivazioni e alternative in [docs/LIMITATIONS.md](docs/LIMITATIONS.md) |

Le voci ⛔ non sono funzioni dichiarate e nascoste: sono requisiti **non realizzati** in questa
release. La tabella finale con stato, verifica e problemi residui è in
[docs/RELEASE-2.0.0.md](docs/RELEASE-2.0.0.md).

## APK

L'APK è pubblicato nell'artifact **ITAGPT-Debug-APK** della run
[44922648963](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44922648963)
(artifactId 14714403936) insieme al checksum `.sha256` e al report `.txt`; test e lint sono
nell'artifact **ITAGPT-CI-Reports**. I valori sotto sono stati ricontrollati dopo il download.

| Campo | Valore |
|---|---|
| File | `ITAGPT-debug-2.0.0.apk` |
| Versione | 2.0.0 (versionCode 8), variante debug, package `com.bigbai.app.debug` |
| Dimensione | **248.208.583 byte (236,71 MiB)** |
| SHA-256 | `fcafa35be77015db7dc14f2d6aeae578629489985028a3690c3cbbec490af292` |
| minSdk / targetSdk | 31 (Android 12) / 35 |
| ABI | arm64-v8a, armeabi-v7a, x86_64 |
| Firma | schema v2, chiave debug `C=US, O=Android, CN=Android Debug` |
| Motore | `libbigbai.so` + `libsqlcipher.so` per ogni ABI |
| Embedding | multilingual-e5-small Q8_0, 132.439.008 byte, SHA-256 verificato |
| Label | `ITAGPT` |

I modelli di **chat** non sono inclusi: si scaricano dall'app con ripresa e verifica SHA-256. Non
esiste alcun limite minimo o massimo artificiale di dimensione: lo script di packaging verifica i
componenti reali, non un numero imposto. L'APK non è stato installato né avviato su un dispositivo:
la verifica è di build, contenuto, firma e artefatto.

## Novità 2.0.0

- **Rebranding completo ITAGPT** con nuova icona adattiva (bolla + “i”, variante monocromatica),
  palette aggiornata, splash, drawer, About, documentazione e nomi degli artifact.
- **Modifica e rigenerazione dei messaggi.** La modifica riscrive il messaggio nel database,
  elimina le risposte successive e rigenera dal nuovo testo; la rigenerazione elimina l'ultima
  risposta e riesegue il turno senza duplicare il messaggio utente.
- **Export TXT e PDF** oltre al Markdown già presente. Il PDF è un documento reale, impaginato e
  selezionabile, scritto localmente con `android.graphics.pdf.PdfDocument`.
- **Voce di base reale:** lettura ad alta voce delle risposte con il motore TTS di sistema
  (interrompibile) e dettatura con il recognizer di sistema, senza aggiungere il permesso
  microfono all'app.
- **Dimensione del testo** impostabile dall'85% al 130%, applicata a tutte le schermate.
- **CI più verificabile:** nessun limite di 200 MiB, checksum ricontrollato, report di build,
  test JUnit e lint caricati come artifact separati.
- Restano dalla 1.5.1: retrieval semantico locale, quality gate, tutor di lingua, backup cifrato,
  ITAGPT Code con sandbox HTML e agente AI, Git locale, diagnostica.

## Funzionalità

**Chat**
- Nuova conversazione, invio, stop, modifica, rigenerazione, copia, condivisione, salvataggio come
  file, eliminazione con codice di conferma, rinomina, ricerca, archiviazione, preferiti, cartelle.
- Rendering Markdown, blocchi di codice, tabelle, elenchi; scroll automatico solo quando l'utente è
  in fondo; conservazione di bozza e posizione dopo rotazione o morte del processo.
- Export conversazione in **Markdown**, **TXT** e **PDF**; salvataggio in `Download/ITAGPT`.
- Allegati immagine e PDF (testo estratto con PDFBox); immagini analizzabili solo con un modello
  multimodale installato.

**AI locale**
- Motore llama.cpp compilato per tre ABI, inferenza CPU con contesto adattivo, token/s e tempo al
  primo token misurati.
- Modello di embedding locale per il retrieval di memorie e documenti, con soglia misurata.
- Quality gate e controlli deterministici (calcolatrice, controllo italiano) mostrati all'utente.
- Prompt con memoria, documenti, fonti web opzionali, lessico del tutor e calcoli verificati.

**ITAGPT Code**
- Editor, file explorer, creazione/rinomina/eliminazione, salvataggio, condivisione.
- Analisi statica reale per HTML, CSS, Java e C++ (bilanciamento, regole strutturali, include guard).
- Analisi multi-file, report delle modifiche, generazione di test, agente con strumenti e checkpoint
  Git, sandbox HTML con rete bloccata per default.
- **Non compila né esegue Java/C++**: Android non include `javac`/`clang` e in questa release non è
  stato integrato un toolchain. L'interfaccia non lo dichiara disponibile.

**Voce (parziale)**
- TTS di sistema per leggere una risposta, con stop esplicito.
- Dettatura tramite l'activity di riconoscimento vocale del dispositivo; il testo viene inserito
  nella casella e inviato solo dall'utente.
- Non sono implementati: conversazione vocale continua, selezione della voce, cronologia vocale.
  L'audio non viene registrato né conservato dall'app.

**Privacy e sicurezza**
- Room + SQLCipher, passphrase casuale protetta da Android Keystore.
- Backup `.bkb` con PBKDF2-HMAC-SHA256 (150.000 iterazioni) e AES-256-GCM.
- Nessuna telemetria, nessun account, nessun upload automatico.
- Rete usata solo per download modelli e ricerca web se abilitata; sandbox HTML senza rete salvo
  autorizzazione.

**Memoria e lingua**
- Ricordi durevoli con conferma, indice semantico e cancellazione coerente dei vettori.
- Tutor italiano → inglese/spagnolo/francese/tedesco/portoghese; il lessico è in-context persistente,
  non addestramento dei pesi.

## Struttura

```
app/    UI Compose, navigazione, ViewModel, servizi (download, notifiche), voce, export
core/   modelli, Room + SQLCipher, crypto/Keystore, impostazioni, repository, PDF, backup
llm/    JNI C++ su llama.cpp/mtmd, LlamaEngine, catalogo e download modelli, embedding
code/   file SAF, analisi statica, patch engine, repository progetti, Git, sandbox HTML
scripts/ fetch-llama.sh, build-native.sh, package-debug-apk.sh
docs/    architettura, build, modelli, limiti, audit e release note
```

Dettagli: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build

Requisiti: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, Gradle 8.11.1.

```bash
bash scripts/fetch-llama.sh          # llama.cpp al commit fissato
bash scripts/fetch-bundled-model.sh  # modello di embedding (132 MB), SHA-256 verificato
bash scripts/build-native.sh         # libbigbai.so per arm64-v8a, armeabi-v7a, x86_64

./gradlew :core:testDebugUnitTest :code:testDebugUnitTest :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew assembleDebug
bash scripts/package-debug-apk.sh    # verifica ABI, modello, firma, manifest, checksum
```

Guida completa: [docs/BUILD.md](docs/BUILD.md). Catalogo modelli: [docs/MODELS.md](docs/MODELS.md).

## Limiti noti

Nessun test su dispositivo fisico o emulatore; accelerazione GPU non inclusa; nessun OCR; nessun
compilatore Java/C++ sul dispositivo; API minima **31 (Android 12)**; voce solo TTS/dettatura.
Elenco completo, con motivazione e alternativa per ogni voce:
[docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## CI

[`.github/workflows/android.yml`](.github/workflows/android.yml) compila la libreria nativa per le
tre ABI, scarica e verifica il modello di embedding, esegue i 214 test JVM, il lint e l'APK debug,
verifica firma, manifest, ABI, modello e checksum, e pubblica:

- **ITAGPT-Debug-APK** — APK + `.sha256` + report `.txt` (30 giorni);
- **ITAGPT-CI-Reports** — risultati JUnit e lint (30 giorni).

Il workflow è avviabile manualmente (`workflow_dispatch`) e non impone soglie di dimensione.
