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
| Compilazione debug e release | ✅ verificate (`assembleDebug`, `assembleRelease`) |
| Test unitari JVM | ✅ 18 test, tutti superati (`:core`, `:code`) |
| Libreria nativa `libbigbai.so` (llama.cpp, arm64-v8a) | ✅ compilata, 5,7 MB (stripped) |
| Inferenza su dispositivo reale | ⛔ non verificata: nessun dispositivo/emulatore disponibile |
| UI su dispositivo | ⛔ non verificata: nessuno screenshot da device |
| Download modelli, SAF, Keystore, sandbox WebView | ⛔ non verificati a runtime |

Le voci ⛔ non sono "funzioni mancanti": sono parti implementate ma **non eseguite** in questo
ambiente di build. I dettagli sono in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## APK

| Campo | Valore |
|---|---|
| File | `BIG-B-AI-1.0.0.apk` |
| Versione | 1.0.0 (versionCode 1) |
| Dimensione | 73.519.963 byte (70,1 MiB) |
| SHA-256 | `54d69e6bd99e03534a697f133b11aa0f5e3b3d27ce40601acf3825ddeac4da3d` |
| minSdk / targetSdk | 31 (Android 12) / 35 |
| ABI | arm64-v8a |
| Firma | chiave **debug** Android (`CN=Android Debug`) |

L'APK è installabile su Android 12+ con architettura arm64. La firma è quella debug perché nel
build host non è presente un keystore di release: è dichiarato, non nascosto. Per pubblicare su
Play Store serve una chiave di release propria (`keystore.properties` o variabili `SIGNING_*`,
vedi [docs/BUILD.md](docs/BUILD.md)).

I modelli **non** sono inclusi nell'APK: si scaricano dall'app, con ripresa del download e verifica
SHA-256. L'APK contiene solo il motore.

## Funzionalità

**Chat**
- Streaming token per token con metriche misurate (token/s, tempo al primo token, RAM).
- Cronologia persistente con cartelle, ricerca, preferiti, rinomina, esportazione.
- Eliminazione conversazione con avviso, codice numerico casuale a 6 cifre e inserimento esatto:
  nessun cestino, l'eliminazione è definitiva.
- Stop della generazione sicuro (annullamento della sessione nativa, non kill del processo).

**Memoria**
- Ricordi durevoli organizzati per categoria, con ricerca e modifica.
- Salvataggio solo con conferma dell'utente; la memoria viene iniettata nel prompt di sistema.

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

Blu e bianco, tema chiaro/scuro/sistema, italiano e inglese. Primo avvio in 5 schermate.
L'interfaccia è attualmente in italiano; il selettore lingua imposta la lingua delle risposte
del modello (la localizzazione completa delle stringhe è nella tabella dei limiti).

## Struttura

```
app/    UI Compose, navigazione, ViewModel, servizi (download, notifiche), onboarding
core/   modelli, Room + SQLCipher, crypto/Keystore, impostazioni, profilo dispositivo,
        repository chat/memoria, PDF, ricerca web, backup
llm/    JNI C++ su llama.cpp, LlamaEngine, catalogo e download modelli, prompt
code/   file SAF, analisi statica, patch engine, repository progetti, Git, sandbox
scripts/ fetch-llama.sh, build-native.sh, package-apk.sh
docs/    architettura, build, modelli, limiti
```

Dettagli: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build rapida

Requisiti: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, Gradle 8.11.1.

```bash
bash scripts/fetch-llama.sh          # scarica llama.cpp al commit fissato
bash scripts/build-native.sh         # produce llm/src/main/jniLibs/arm64-v8a/libbigbai.so
gradle :core:testDebugUnitTest :code:testDebugUnitTest
gradle assembleDebug                 # APK di debug
gradle assembleRelease && bash scripts/package-apk.sh
```

Guida completa: [docs/BUILD.md](docs/BUILD.md). Catalogo modelli: [docs/MODELS.md](docs/MODELS.md).

## Privacy

- Il database è cifrato con SQLCipher; la passphrase è casuale (32 byte) e protetta da Android Keystore.
- Nessuna telemetria, nessun account, nessun upload automatico di conversazioni o codice.
- Le uniche connessioni di rete sono: download dei modelli scelti e ricerca web se configurata.
- La sandbox dei progetti non ha rete salvo autorizzazione esplicita.

## Limiti noti

Nessun test su dispositivo fisico o emulatore; accelerazione GPU non inclusa; nessun compilatore
Java/C++ sul dispositivo; nessun OCR. Elenco completo e motivato in
[docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## CI

`.github/workflows/android.yml` compila la libreria nativa, esegue i test JVM, produce APK debug e
release e pubblica l'APK come artifact con checksum.
