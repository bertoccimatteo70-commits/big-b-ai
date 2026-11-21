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
| Test unitari JVM | ✅ 39 test, tutti superati (`:core`, `:code`) |
| Libreria nativa `libbigbai.so` (llama.cpp, arm64-v8a) | ✅ compilata, 5,7 MB (stripped) |
| Inferenza su dispositivo reale | ⛔ non verificata: nessun dispositivo/emulatore disponibile |
| UI su dispositivo | ⛔ non verificata: nessuno screenshot da device |
| Download modelli, SAF, Keystore, sandbox WebView | ⛔ non verificati a runtime |

Le voci ⛔ non sono "funzioni mancanti": sono parti implementate ma **non eseguite** in questo
ambiente di build. I dettagli sono in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## APK

| Campo | Valore |
|---|---|
| File | `BIG-B-AI-debug-1.2.0.apk` |
| Versione | 1.2.0-debug (versionCode 3) |
| Dimensione | 92.821.934 byte (88,5 MiB) |
| SHA-256 | `7b8a5a390d05034ef4bb0534657e78fb959e8b2a79128ef3768fe68598d5d808` |
| minSdk / targetSdk | 31 (Android 12) / 35 |
| ABI | arm64-v8a |
| Firma | chiave **debug** Android (`CN=Android Debug`) |

L'APK è installabile su Android 12+ con architettura arm64. È la variante **debug**
(`com.bigbai.app.debug`), firmata con la chiave debug Android (`CN=Android Debug`): è dichiarato,
non nascosto. I valori qui sopra sono la build locale di riferimento; la CI produce un APK
equivalente e lo pubblica come artifact `BIG-B-AI-Debug-APK` (retention 30 giorni) con il proprio
file `.sha256` accanto. L'hash può cambiare tra una build e l'altra per i timestamp di firma.

I modelli **non** sono inclusi nell'APK: si scaricano dall'app, con ripresa del download e verifica
SHA-256. L'APK contiene solo il motore.

## Funzionalità

**Chat**
- Streaming fluido con aggiornamenti UI limitati a ~25 fps e metriche misurate (token/s, tempo al
  primo token, RAM di processo).
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
- Progetti su cartelle autorizzate via SAF (nessun accesso fuori dalla cartella scelta).
- Editor, file explorer, creazione/eliminazione file, salvataggio.
- Analisi statica reale per HTML, CSS, Java e C++ (bilanciamento, regole strutturali, include guard).
- Assistente AI con protocollo di patch esplicito: ogni modifica mostra il diff e richiede
  approvazione prima di essere scritta.
- Git locale (JGit): commit snapshot, cronologia, diff, ripristino, branch.
- Sandbox HTML in WebView usa-e-getta: rete bloccata per default, timeout 8 s, nessun accesso ai file.
- Memoria di progetto separata (obiettivi, decisioni, errori, stato).

## Interfaccia

Design system indigo/ciano, tema chiaro/scuro/sistema, tipografia e forme coerenti, transizioni
brevi disattivabili con «Riduci animazioni». Primo avvio in 5 schermate. L'interfaccia è
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

Dettagli: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Audit 1.2.0 con cause, correzioni,
metriche e limiti verificati: [docs/AUDIT-1.2.md](docs/AUDIT-1.2.md); audit precedente:
[docs/AUDIT-1.1.md](docs/AUDIT-1.1.md).

## Build rapida

Requisiti: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, Gradle 8.11.1.

```bash
bash scripts/fetch-llama.sh          # scarica llama.cpp al commit fissato
bash scripts/build-native.sh         # produce llm/src/main/jniLibs/arm64-v8a/libbigbai.so
./gradlew :core:testDebugUnitTest :code:testDebugUnitTest :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew assembleDebug              # APK di debug
bash scripts/package-debug-apk.sh    # dist/ + SHA-256 + metadata
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
compila la libreria nativa, esegue i test JVM, produce l'APK debug
e lo pubblica come artifact **BIG-B-AI-Debug-APK** (APK + SHA-256, retention 30 giorni). Il setup
dell'SDK usa `android-actions/setup-android@v4`, che non richiede più il pacchetto `tools` rimosso
da Google (causa del precedente errore "Failed to find package 'tools'").
