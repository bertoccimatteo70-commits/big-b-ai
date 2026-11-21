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
| Test unitari JVM | ✅ 91 test, 0 falliti (`:core` 26, `:code` 26, `:llm` 23, `:app` 16) |
| Lint debug | ✅ 0 errori, 66 warning (dipendenze/AGP non aggiornate) |
| Copertura JVM (JaCoCo) | ✅ misurata: **8,0%** delle linee complessive; logica pura coperta (core/chat 94,7%, core/lang 99,1%, code 33,7%, llm 23,5%), UI e Room non coperte da test JVM |
| Librerie native `libbigbai.so` (llama.cpp) | ✅ compilate e strippate per **arm64-v8a, armeabi-v7a e x86_64** (4,2–6,6 MB ciascuna) |
| Inferenza su dispositivo reale | ⛔ non verificata: nessun dispositivo/emulatore disponibile |
| UI su dispositivo | ⛔ non verificata: nessuno screenshot da device |
| Download modelli, SAF, Keystore, sandbox WebView | ⛔ non verificati a runtime |

Le voci ⛔ non sono "funzioni mancanti": sono parti implementate ma **non eseguite** in questo
ambiente di build. I dettagli sono in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## APK

| Campo | Valore |
|---|---|
| File | `BIG-B-AI-debug-1.3.0.apk` |
| Versione | 1.3.0-debug (versionCode 4) |
| Dimensione | **114.028.656 byte (108,75 MiB / 114,03 MB)** |
| SHA-256 | `170905bb50813a4d6e7e619c27fec40e123604bcb33f0f2b109f33558a76badf` |
| minSdk / targetSdk | 31 (Android 12) / 35 |
| ABI | **arm64-v8a, armeabi-v7a, x86_64** (APK universale) |
| Firma | chiave **debug** Android (`CN=Android Debug`) |

L'APK è installabile su Android 12+ con architettura ARM 64-bit, ARM 32-bit o x86_64 (emulatore).
È la variante **debug** (`com.bigbai.app.debug`), firmata con la chiave debug Android
(`CN=Android Debug`): è dichiarato, non nascosto. I valori qui sopra sono l'APK pubblicato come
artifact `BIG-B-AI-Debug-APK` dalla run CI
[44894826291](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44894826291) sul
commit `606797a`; `.sha256` e `.txt` sono accanto all'APK nello stesso artifact. L'hash cambia a
ogni ricostruzione per i timestamp di firma.

La dimensione supera i 100 MB perché l'APK contiene il motore nativo reale per **tre** ABI: non
c'è alcun file aggiunto per gonfiarlo. `scripts/package-debug-apk.sh` verifica la presenza di
`libbigbai.so` e `libsqlcipher.so` in ogni ABI e rifiuta un APK sotto i 100 MiB.

I modelli **non** sono inclusi nell'APK: si scaricano dall'app, con ripresa del download e verifica
SHA-256. L'APK contiene solo il motore.

## Funzionalità

**Chat**
- Menu laterale unico con dieci destinazioni (nuova chat, cronologia, ricerca, BIG B Code,
  libreria/download/installati, plugin, impostazioni, informazioni) e conversazioni recenti.
- Ogni nuova chat parte da zero: nessun messaggio, allegato, bozza, metrica o errore ereditato;
  anche il passaggio tra conversazioni azzera lo stato e riporta la lista in cima.
- Bolle bianche `#FFFFFF` con testo nero e font serif in entrambi i temi; il codice resta
  monospaziato.
- Streaming fluido con aggiornamenti UI limitati a ~25 fps e metriche misurate (token/s, tempo al
  primo token, RAM di processo).
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
- Assistente AI con protocollo di patch esplicito: ogni modifica mostra il diff e richiede
  approvazione prima di essere scritta.
- Git locale (JGit): commit snapshot, cronologia, diff, ripristino, branch.
- Sandbox HTML in WebView usa-e-getta: rete bloccata per default, timeout 8 s, nessun accesso ai file.
- Memoria di progetto separata (obiettivi, decisioni, errori, stato).

## Interfaccia

Design system indigo/ciano, tema chiaro/scuro/sistema, tipografia e forme coerenti, transizioni
brevi disattivabili con «Riduci animazioni». Primo avvio in 5 schermate. Navigazione a menu
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
