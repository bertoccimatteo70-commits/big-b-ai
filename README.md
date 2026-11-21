# BIG B AI

Assistente AI locale per Android: chat con memoria persistente, modelli GGUF eseguiti sul
dispositivo tramite llama.cpp, e un ambiente di sviluppo integrato (**BIG B Code**) per lavorare
su progetti HTML, Java e C++ senza inviare codice a servizi esterni.

Nessun account, nessun cloud obbligatorio, nessun dato finto: tutto ciò che l'interfaccia mostra
viene da calcoli reali, dal database locale o dal motore di inferenza.

---

## Stato del progetto

| Area | Stato |
|---|---|
| Compilazione debug | ✅ verificata (`assembleDebug`); APK generato, firmato e ispezionato |
| Test unitari JVM | ✅ 165 test, 0 falliti (`:core` 42, `:code` 48, `:llm` 59, `:app` 16) |
| Lint debug | ✅ 0 errori, 66 warning (dipendenze/AGP non aggiornate) |
| Copertura JVM (JaCoCo) | ✅ misurata in 1.3.0: **8,0%** delle linee complessive; la copertura non è stata ricalcolata in 1.5.0 |
| Librerie native `libbigbai.so` (llama.cpp) | ✅ compilate e strippate per **arm64-v8a, armeabi-v7a e x86_64** (4,2–6,6 MB ciascuna) |
| Modello di embedding incluso | ✅ multilingual-e5-small Q8_0, 132.439.008 byte, SHA-256 verificato nello script, nell'APK e a runtime |
| Embedding su host | ✅ caricamento e vettori a 384 dimensioni verificati su x86_64; su dispositivo **non verificato** |
| Inferenza su dispositivo reale | ⛔ non verificata: nessun dispositivo/emulatore disponibile |
| UI su dispositivo | ⛔ non verificata: nessuno screenshot da device |
| Download modelli, SAF, Keystore, sandbox WebView | ⛔ non verificati a runtime |

Le voci ⛔ non sono "funzioni mancanti": sono parti implementate ma **non eseguite** in questo
ambiente di build. I dettagli sono in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## APK

| Campo | Valore |
|---|---|
| File | `BIG-B-AI-debug-1.5.0.apk` |
| Versione | 1.5.0-debug (versionCode 6) |
| Dimensione | **248.075.965 byte (236,58 MiB / 248,08 MB)** |
| SHA-256 | `23a47f3be138c6742da27392eb7ddd9f1b6d64a75419a10b6df00023ca05e064` |
| minSdk / targetSdk | 31 (Android 12) / 35 |
| ABI | **arm64-v8a, armeabi-v7a, x86_64** (APK universale) |
| Firma | chiave **debug** Android (`CN=Android Debug`) |
| Requisito 200 MiB | **soddisfatto**: 209.715.200 byte richiesti, 248.075.965 presenti |

L'APK è installabile su Android 12+ con architettura ARM 64-bit, ARM 32-bit o x86_64 (emulatore).
È la variante **debug** (`com.bigbai.app.debug`), firmata con la chiave debug Android
(`CN=Android Debug`): è dichiarato, non nascosto. I valori qui sopra sono l'APK scaricato
dall'artifact `BIG-B-AI-Debug-APK` della run CI
[44904141926](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44904141926) sul
commit `f27d723`; dimensione, SHA-256, firma, manifest, ABI e modello incluso sono stati
ricontrollati dopo il download. L'hash cambia a ogni ricostruzione per i timestamp di firma.

La dimensione supera i 200 MiB perché l'APK contiene il motore nativo reale per **tre** ABI e il
modello di embedding **multilingual-e5-small Q8_0** (132.439.008 byte) usato per il retrieval
locale: non c'è alcun file aggiunto per gonfiarlo. `scripts/package-debug-apk.sh` verifica la
presenza di `libbigbai.so` e `libsqlcipher.so` in ogni ABI, l'integrità del modello dentro l'APK,
firma e manifest, e **rifiuta un APK sotto i 200 MiB**.

I modelli di **chat** non sono inclusi nell'APK: si scaricano dall'app, con ripresa del download e
verifica SHA-256. L'APK contiene il motore e il modello di embedding.

## Novità 1.5.0

- **Retrieval semantico locale.** Il modello di embedding multilingual-e5-small Q8_0 è incluso
  nell'APK: documenti e memorie vengono indicizzati e i passaggi pertinenti entrano nel prompt,
  filtrati per conversazione. Soglia alta (0,80) per non iniettare testo fuorviante.
- **Controlli deterministici.** Le espressioni aritmetiche vengono calcolate dall'app e il
  risultato verificato entra nel prompt; un controllo italiano segnala spazi, duplicati,
  punteggiatura e bilanciamento; un quality gate riscrive una sola volta le risposte ripetitive
  o con codice non chiuso.
- **Sampler più fine.** Repetition penalty e min-p sono parametri nativi, con valori per famiglia
  di modello e regolazione manuale nelle impostazioni avanzate.
- **BIG B Code più concreto.** Analisi multi-file con duplicati, dipendenze mancanti, TODO e file
  grandi; report delle modifiche; sandbox automatica per l'HTML; generazione di test; azioni
  rapide nella chat.
- **Diagnostica.** Motore, ABI, modello attivo, RAM, CPU, token/s, tempo al primo token, stato e
  integrità del modello di embedding, passaggi indicizzati, ultimo errore.
- **Memoria semantica** con interruttore nelle impostazioni; la cancellazione di un ricordo
  cancella anche i suoi vettori.
- **APK universale da 237,14 MiB**, sopra il requisito di 200 MiB, con tre ABI reali e il modello
  di embedding verificato.

Dettagli e misure in [docs/AUDIT-1.5.md](docs/AUDIT-1.5.md); audit precedenti:
[docs/AUDIT-1.4.md](docs/AUDIT-1.4.md), [docs/AUDIT-1.3.md](docs/AUDIT-1.3.md).

## Funzionalità

**Chat**
- Menu laterale unico con dieci destinazioni (nuova chat, cronologia, ricerca, BIG B Code,
  libreria/download/installati, plugin, impostazioni, informazioni) e conversazioni recenti.
- Ogni nuova chat parte da zero: nessun messaggio, allegato, bozza, metrica o errore ereditato;
  anche il passaggio tra conversazioni azzera lo stato e riporta la lista in cima.
- Bolle bianche `#FFFFFF` con testo nero e font Tinos (metricamente compatibile con Times New
  Roman) in entrambi i temi; il codice resta monospaziato.
- Risposta generata in background e mostrata **completa**, con indicatore animato, annullamento,
  timeout e metriche misurate (token/s, tempo al primo token, RAM di processo).
- **Retrieval locale**: i passaggi pertinenti di documenti e memorie vengono recuperati per
  similarità e citati nel prompt; la barra mostra quanti sono stati usati.
- Salvataggio automatico di una risposta come file (nome generato, estensione dal linguaggio del
  blocco di codice) in `Download/BIG B AI`, condivisione e esportazione dell'intera conversazione
  in Markdown.
- La conversazione resta aperta tra un messaggio e l'altro, conserva cronologia, bozza e stato anche
  dopo rotazione o morte del processo; interruzione della generazione senza perdere il testo.
- Cronologia persistente con cartelle, ricerca, preferiti, rinomina.
- Eliminazione conversazione con avviso, codice numerico casuale a 6 cifre e inserimento esatto:
  nessun cestino, l'eliminazione è definitiva.
- Stop della generazione sicuro (annullamento della sessione nativa, non kill del processo).

**Memoria**
- Ricordi durevoli organizzati per categoria, con ricerca e modifica.
- Salvataggio solo con conferma dell'utente; la memoria viene iniettata nel prompt di sistema.
- **Indice semantico** dei ricordi: recupero per similarità, pulizia automatica alla cancellazione,
  interruttore nelle impostazioni.

**Tutor di lingua**
- Lezioni italiano → inglese, spagnolo, francese, tedesco, portoghese, con tre livelli.
- Glossario curato iniettato nel prompt e lessico personale salvato nel database cifrato
  (categoria `Lingua:<CODICE>`), reiniettato nelle lezioni successive.
- Non riaddestra i pesi del modello: è apprendimento in-context persistente, dichiarato nell'app.

**Modelli**
- Tre schede: **Libreria** (catalogo e profili), **Download** (progresso, pausa, ripresa,
  annullamento con rimozione dei file parziali) e **Installati** (modello attivo, verifica,
  eliminazione).
- Catalogo con 7 famiglie Qwen2.5 (Instruct, Coder, VL) e quantizzazioni Q2–Q6/Q8.
- Quattro profili: Veloce, Equilibrato, Intelligente, Massimo.
- Download riprendibile, verifica SHA-256 del file completo, installazione atomica.
- Budget RAM al 50% (Normale) o 70% (Avanzato), sempre subordinato alla memoria disponibile.
- Context length adattiva in base a RAM, parametri del modello e KV cache.

**Contenuti**
- PDF: estrazione testo reale con PDFBox-Android (nessun OCR: i PDF scansionati non sono leggibili).
- Immagini: solo con un modello multimodale reale (Qwen2.5-VL + proiettore `mmproj` via `libmtmd`).
- Ricerca web opzionale (SearXNG o Brave), disattivata per default.

**Backup**
- File `.bkb` cifrato con password: PBKDF2-HMAC-SHA256 (150.000 iterazioni) + AES-256-GCM.
- Ripristinabile su un altro dispositivo; deduplica tramite hash del contenuto.

**BIG B Code**
- «Nuovo progetto» crea cartella e `index.html` automaticamente, senza selettori né percorsi da
  inventare; resta disponibile l'apertura di una cartella esistente via SAF.
- Editor, file explorer, creazione/rinomina/eliminazione file, salvataggio, esportazione in
  Download e condivisione del file aperto.
- Analisi statica reale per HTML, CSS, Java e C++ (bilanciamento, regole strutturali, include guard).
- **Analisi del progetto** su più file: blocchi duplicati, dipendenze importate ma non dichiarate,
  `TODO`/`FIXME`, file molto grandi, pattern a rischio; report delle modifiche con esito dei
  controlli e sandbox automatica per l'HTML; generazione di test.
- Agente AI con strumenti reali: crea, legge, elenca ed elimina file del progetto, fino a 5 passi
  autonomi, con activity log, checkpoint Git prima della prima scrittura e conferma obbligatoria
  per le eliminazioni. Massimo 5 richieste al giorno, visibili e persistenti.
- Git locale (JGit): commit snapshot, cronologia, diff, ripristino, branch.
- Sandbox HTML in WebView usa-e-getta: rete bloccata per default, timeout 8 s, nessun accesso ai file.
- Memoria di progetto separata (obiettivi, decisioni, errori, stato).

## Interfaccia

Design system indigo/ciano, tema chiaro/scuro/sistema, tipografia Tinos e forme coerenti,
transizioni brevi disattivabili con «Riduci animazioni» o con l'opzione di sistema Android. Primo avvio in 5 schermate. Navigazione a menu
laterale, con le funzioni principali a un tocco. Le bolle della chat sono bianche con testo nero
e font serif per scelta esplicita di leggibilità, anche in modalità scura. L'interfaccia è
tradotta in italiano; il selettore lingua imposta la lingua delle risposte del modello (la
localizzazione completa delle stringhe è nella tabella dei limiti). Il tutor di lingua si
configura dalle impostazioni e si usa dalla chat.

## Struttura

```
app/    UI Compose, navigazione, ViewModel, servizi (download, notifiche), onboarding
core/   modelli, Room + SQLCipher, crypto/Keystore, impostazioni, profilo dispositivo,
        repository chat/memoria, PDF, ricerca web, backup
llm/    JNI C++ su llama.cpp, LlamaEngine, catalogo e download modelli, prompt
code/   file SAF, analisi statica, patch engine, repository progetti, Git, sandbox
scripts/ fetch-llama.sh, build-native.sh, package-debug-apk.sh
docs/    architettura, build, modelli, limiti
```

Dettagli: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Audit 1.3.0 con cause, correzioni,
metriche e limiti verificati: [docs/AUDIT-1.3.md](docs/AUDIT-1.3.md); audit precedenti:
[docs/AUDIT-1.2.md](docs/AUDIT-1.2.md), [docs/AUDIT-1.1.md](docs/AUDIT-1.1.md).

## Build rapida

Requisiti: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, Gradle 8.11.1.

```bash
bash scripts/fetch-llama.sh          # scarica llama.cpp al commit fissato
bash scripts/fetch-bundled-model.sh  # scarica e verifica il modello di embedding (132 MB)
bash scripts/build-native.sh         # produce libbigbai.so per arm64-v8a, armeabi-v7a, x86_64
./gradlew :core:testDebugUnitTest :code:testDebugUnitTest :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug             # lint (0 errori attesi)
./gradlew jacocoTestReport           # copertura JVM aggregata (build/reports/jacoco)
./gradlew assembleDebug              # APK di debug
bash scripts/package-debug-apk.sh    # verifica ABI, modello, firma, manifest e >=200 MiB
```

Guida completa: [docs/BUILD.md](docs/BUILD.md). Catalogo modelli: [docs/MODELS.md](docs/MODELS.md).

## Privacy

- Il database è cifrato con SQLCipher; la passphrase è casuale (32 byte) e protetta da Android Keystore.
- Nessuna telemetria, nessun account, nessun upload automatico di conversazioni o codice.
- Le uniche connessioni di rete sono: download dei modelli scelti e ricerca web se configurata.
- La sandbox dei progetti non ha rete salvo autorizzazione esplicita.

## Limiti noti

Nessun test su dispositivo fisico o emulatore; accelerazione GPU non inclusa; nessun compilatore
Java/C++ sul dispositivo; nessun OCR; il tutor linguistico non addestra i pesi. Elenco completo e
motivato in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## CI

[`.github/workflows/android.yml`](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/workflows/android.yml)
compila la libreria nativa per le tre ABI, scarica e verifica il modello di embedding, esegue i
165 test JVM dei quattro moduli, esegue il lint, produce l'APK debug, ne verifica firma, manifest,
ABI e dimensione minima di 200 MiB e lo pubblica come artifact **BIG-B-AI-Debug-APK** (APK +
SHA-256 + metadata, retention 30 giorni). Il setup
dell'SDK usa `android-actions/setup-android@v4`, che non richiede più il pacchetto `tools` rimosso
da Google (causa del precedente errore "Failed to find package 'tools'").
