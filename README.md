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
| Test unitari JVM | ✅ 124 test, 0 falliti (`:core` 26, `:code` 42, `:llm` 40, `:app` 16) |
| Lint debug | ✅ 0 errori, 66 warning (dipendenze/AGP non aggiornate) |
| Copertura JVM (JaCoCo) | ✅ misurata in 1.3.0: **8,0%** delle linee complessive; la copertura non è stata ricalcolata in 1.4.0 |
| Librerie native `libbigbai.so` (llama.cpp) | ✅ compilate e strippate per **arm64-v8a, armeabi-v7a e x86_64** (4,2–6,6 MB ciascuna) |
| Inferenza su dispositivo reale | ⛔ non verificata: nessun dispositivo/emulatore disponibile |
| UI su dispositivo | ⛔ non verificata: nessuno screenshot da device |
| Download modelli, SAF, Keystore, sandbox WebView | ⛔ non verificati a runtime |

Le voci ⛔ non sono "funzioni mancanti": sono parti implementate ma **non eseguite** in questo
ambiente di build. I dettagli sono in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## APK

| Campo | Valore |
|---|---|
| File | `BIG-B-AI-debug-1.4.0.apk` |
| Versione | 1.4.0-debug (versionCode 5) |
| Dimensione | **115.341.878 byte (110,00 MiB / 115,34 MB)** |
| SHA-256 | `bc54c237439581063cad3a6374fe1ef25db7beb8484f40d2d0038ba3dcd1f67b` |
| minSdk / targetSdk | 31 (Android 12) / 35 |
| ABI | **arm64-v8a, armeabi-v7a, x86_64** (APK universale) |
| Firma | chiave **debug** Android (`CN=Android Debug`) |

L'APK è installabile su Android 12+ con architettura ARM 64-bit, ARM 32-bit o x86_64 (emulatore).
È la variante **debug** (`com.bigbai.app.debug`), firmata con la chiave debug Android
(`CN=Android Debug`): è dichiarato, non nascosto. I valori qui sopra sono la build locale di
riferimento della 1.4.0; la CI ricompila lo stesso codice e pubblica l'APK come artifact
`BIG-B-AI-Debug-APK` con `.sha256` e `.txt` accanto. L'hash cambia a ogni ricostruzione per i
timestamp di firma.

La dimensione supera i 100 MB perché l'APK contiene il motore nativo reale per **tre** ABI: non
c'è alcun file aggiunto per gonfiarlo. `scripts/package-debug-apk.sh` verifica la presenza di
`libbigbai.so` e `libsqlcipher.so` in ogni ABI e rifiuta un APK sotto i 100 MiB.

I modelli **non** sono inclusi nell'APK: si scaricano dall'app, con ripresa del download e verifica
SHA-256. L'APK contiene solo il motore.

## Novità 1.4.0

- **BIG B Code è un agente.** Descrivi l'obiettivo: crea i file, scrive il codice, legge ciò che
  serve, controlla il risultato e corregge, fino a 5 passi autonomi. Ogni azione compare in un
  activity log con esito reale; le eliminazioni chiedono conferma.
- **5 richieste al giorno**, persistenti e visibili ovunque. Una richiesta = una attività: i
  passaggi interni non consumano nulla. Il limite è locale al dispositivo (nessun account).
- **Risposte complete.** Niente token in streaming: un indicatore animato, poi la risposta intera
  con animazione. Annullamento e timeout sono marcati, gli errori non diventano risposte finte.
- **Font Tinos** (Apache-2.0), metricamente compatibile con Times New Roman, su tutta l'interfaccia;
  Times New Roman è proprietario e non distribuibile.
- **Profili per modello**: temperatura, top-p, top-k, contesto e lunghezza dipendono dal modello;
  chat e BIG B Code hanno scelte indipendenti e visibili.
- **Animazioni sobrie** rispettose dell'opzione Android «rimuovi animazioni».

Dettagli e misure in [docs/AUDIT-1.4.md](docs/AUDIT-1.4.md).

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
bash scripts/build-native.sh         # produce libbigbai.so per arm64-v8a, armeabi-v7a, x86_64
./gradlew :core:testDebugUnitTest :code:testDebugUnitTest :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug             # lint (0 errori attesi)
./gradlew jacocoTestReport           # copertura JVM aggregata (build/reports/jacoco)
./gradlew assembleDebug              # APK di debug
bash scripts/package-debug-apk.sh    # verifica ABI e >=100 MiB, dist/ + SHA-256 + metadata
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
compila la libreria nativa per le tre ABI, esegue i 91 test JVM dei quattro moduli, esegue il lint,
produce l'APK debug e lo pubblica come artifact **BIG-B-AI-Debug-APK** (APK + SHA-256 + metadata,
retention 30 giorni). Il setup
dell'SDK usa `android-actions/setup-android@v4`, che non richiede più il pacchetto `tools` rimosso
da Google (causa del precedente errore "Failed to find package 'tools'").
