# Audit BIG B AI 1.3.0

Revisione completa del repository con interventi diretti su codice, build e interfaccia.
Ogni numero in questo documento è misurato in questo ambiente; ciò che non è stato eseguito è
indicato come **NON VERIFICATO**.

## 1. Metodo

1. Lettura integrale dei moduli `app`, `core`, `llm`, `code`, della build Gradle e della pipeline.
2. Riproduzione della build nativa per ogni ABI prevista e ispezione dell'APK prodotto.
3. Interventi mirati, con test unitari per la logica pura introdotta o modificata.
4. Verifica finale: test JVM, lint, `assembleDebug`, contenuto dell'APK, SHA-256.

Ambiente: Linux x86_64, 8 core, 15 GB RAM, JDK 17.0.21.1+2, Android SDK 35, NDK 27.2.12479018,
CMake 3.22.1, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, llama.cpp v1.0.0
(`b1b770d90d94cad9e9ea5b993bdbc383a522fb28`).

## 2. Dimensione dell'APK: causa e soluzione

**Requisito:** APK debug ≥ 100 MB, ottenuto con componenti reali.

**Causa della dimensione precedente.** L'APK 1.2.0 pesava 92.267.940 byte (88,0 MiB) e conteneva
una sola ABI, `arm64-v8a`: `ndk.abiFilters` era limitato a quella architettura in
`app/build.gradle.kts` e `llm/build.gradle.kts`, e `scripts/build-native.sh` compilava un solo
`.so`. Il file ZIP dell'artifact GitHub era di 33 MB perché il contenuto è comprimibile: la
dimensione dell'APK non era 33 MB.

**Soluzione adottata.** L'APK è ora universale e contiene il motore nativo reale per tre
architetture:

| ABI | `libbigbai.so` (stripped) | `libsqlcipher.so` | Destinazione |
|---|---:|---:|---|
| `arm64-v8a` | 5.945.248 B | 5.797.528 B | telefoni e tablet 64-bit (target primario) |
| `armeabi-v7a` | 4.184.696 B | 4.035.640 B | dispositivi 32-bit |
| `x86_64` | 6.611.800 B | 6.372.968 B | emulatore Android |

Nessun file è stato aggiunto per gonfiare l'APK: ogni ABI è una libreria compilata da sorgenti
llama.cpp e usata a runtime da quella architettura. `x86_64` rende l'app eseguibile nell'emulatore,
`armeabi-v7a` copre i dispositivi 32-bit che prima installavano l'APK e andavano in crash con
`UnsatisfiedLinkError`.

**Risultato misurato:** `dist/BIG-B-AI-debug-1.3.0.apk` = **119.987.886 byte = 114,43 MiB =
119,99 MB**. Verificato con `unzip -l`: presenti `libbigbai.so` e `libsqlcipher.so` per tutte e tre
le ABI; lo SHA-256 di ogni `.so` nell'APK coincide con quello del file compilato in
`llm/src/main/jniLibs/`.

**Problema tecnico incontrato e risolto.** La build `armeabi-v7a` falliva in
`ggml-cpu/llamafile/sgemm.cpp` (`vld1q_f16` non dichiarato): i kernel FP16 NEON di llamafile
richiedono ARMv8. `llm/src/main/cpp/CMakeLists.txt` ora disattiva `GGML_LLAMAFILE` solo per
`armeabi-v7a`; le altre ABI mantengono i kernel ottimizzati.

## 3. Interfaccia e navigazione

### 3.1 Menu laterale

Creato `app/src/main/kotlin/com/bigbai/app/ui/AppDrawer.kt`, ospitato una sola volta sopra il
grafo di navigazione (`AppRoot`). Contiene le dieci voci richieste:

1. Nuova chat (azione, azzera la chat)
2. Cronologia
3. Ricerca conversazioni (apre la cronologia con il campo di ricerca già a fuoco)
4. BIG B Code
5. Libreria modelli AI
6. Download modelli
7. Modelli installati
8. Plugin e integrazioni
9. Impostazioni
10. Informazioni sull'app

Sotto le voci compaiono le sei conversazioni recenti. Il menu è raggiungibile dall'icona in alto a
sinistra in chat, cronologia, modelli, BIG B Code, memoria, impostazioni, plugin e informazioni.
Il vecchio drawer conteneva solo la cronologia: modelli, codice e impostazioni erano nascosti in
menu overflow.

Nuove schermate: `ui/history/HistoryScreen.kt` (cronologia completa con ricerca, cartelle,
preferiti, rinomina, archiviazione ed eliminazione), `ui/plugins/PluginsScreen.kt` (integrazioni
realmente presenti), `ui/about/AboutScreen.kt` (versione, motore, dispositivo, privacy, licenze).
`ModelManagerScreen` è stato diviso in tre schede: Libreria, Download, Installati.

### 3.2 Bug del pulsante «+»

**Sintomo riportato:** una nuova chat ereditava stato dalla precedente.

**Cause individuate nel codice 1.2.0:**

- `newChat()` copiava a mano un sottoinsieme di campi e non azzerava `isGenerating`,
  `isThinking`, `status` e `lexiconSaved`;
- se una generazione era in corso, il pulsante si limitava a mostrare un errore invece di aprire
  una nuova chat;
- il callback di streaming e i blocchi di completamento aggiornavano `_state` senza verificare se
  la conversazione fosse ancora quella aperta: la coda della risposta precedente poteva dipingere
  `metrics`, `error` e `streamingText` nella chat nuova;
- `openConversation()` non azzerava bozza e allegati, che quindi passavano alla conversazione
  successiva;
- la lista conservava la posizione di scorrimento della chat precedente.

**Correzioni:**

- `ChatStateTransitions.kt`: `afterNewChat()` ricostruisce `ChatUiState()` conservando solo lo
  stato globale del modello; `afterOpenConversation(id)` azzera tutto lo stato per-conversazione.
- `ChatViewModel.newChat()` ferma il motore, annulla il job, azzera la sessione e sostituisce lo
  stato: il pulsante funziona sempre, anche durante una generazione.
- Contatore `activeGeneration`: ogni aggiornamento della UI passa da `updateIfCurrent(...)`;
  `restoreDraft` ignora le generazioni non più attive.
- `ChatScreen`: `LaunchedEffect(state.conversationId)` riporta la lista in cima e riattiva il
  follow del fondo.
- Test: `ChatStateTransitionsTest` (3 test) verifica che messaggi, allegati, bozza, metriche,
  errori, fonti e lessico non vengano ereditati.

### 3.3 Bolle di chat

`MessageBubble` e `StreamingBubble` usano ora **sfondo bianco puro `#FFFFFF`, testo nero
`#000000` e font serif** in entrambi i temi; il codice recintato mantiene un blocco monospaziato
distinto (sfondo `#F1F3F7`, testo `#111111`). Il font è `FontFamily.Serif` di Android: Times New
Roman non è distribuibile con l'app, quindi si usa il serif di sistema, come previsto dal
requisito. Le stesse regole sono applicate alle bolle della chat di BIG B Code.

## 4. Autonomia dell'AI e gestione file

- **Salvataggio automatico delle risposte.** Ogni bolla ha le azioni «Salva come file» e
  «Condividi». Il nome è generato da titolo + data (`bigb-<slug>-<yyyyMMdd-HHmmss>.<ext>`) e
  l'estensione deriva dal primo blocco di codice (`kotlin` → `.kt`, `python` → `.py`, altrimenti
  `.md`). Il file viene scritto in `Download/BIG B AI` via MediaStore, senza permessi di storage.
- **Esportazione conversazione.** Il menu overflow della chat salva l'intera conversazione in
  Markdown con nome automatico.
- **BIG B Code senza percorsi.** «Nuovo progetto» crea una cartella `progetto-<data>` nella
  cartella documenti dell'app, scrive `index.html` con una pagina funzionante e apre il progetto:
  nessun selettore di cartelle, nessun nome da inventare. Resta disponibile l'apertura di una
  cartella esistente via SAF.
- **Rinomina ed esportazione file.** Nel tab File è possibile rinominare un file; nell'editor si
  può esportarlo in Download o condividerlo.
- **Errori.** Ogni operazione fallita produce un messaggio leggibile (spazio insufficiente, file
  non scrivibile, condivisione non disponibile) invece di un crash.
- Test: `FileExportTest` (8 test) copre slug, timestamp, estensioni e MIME; `ProjectNamingTest`
  (4 test) copre nome cartella, HTML iniziale ed escaping.

## 5. Modelli AI

`ModelManagerScreen` è ora diviso in tre schede:

- **Libreria**: catalogo per profilo (Veloce, Equilibrato, Intelligente, Massimo) e modalità
  avanzata con quantizzazione, dimensione, RAM stimata, context, licenza e provenienza.
- **Download**: download in corso, in pausa e falliti con percentuale, byte trasferiti, pausa,
  ripresa e **annulla** (rimuove i file parziali); scelte rapide per iniziare.
- **Installati**: modelli presenti, modello attivo, verifica SHA-256 ed eliminazione.

Controlli già presenti e mantenuti: spazio libero prima del download, ripresa con HTTP Range,
verifica SHA-256 prima dell'installazione, budget RAM calcolato sulla memoria disponibile,
context adattiva. `ModelStore.discardPartial()` è nuovo e cancella solo i `.part`.

## 6. Qualità della generazione

Interventi sul percorso UI → ViewModel → prompt → JNI → llama.cpp → sampler → streaming:

| Problema | Correzione |
|---|---|
| Loop e ripetizioni con modelli piccoli | Aggiunto sampler di penalità (`penalty_last_n=64`, `repeat=1.12`) prima di top-k/top-p/temperatura in `bigbai_jni.cpp`. |
| Doppio BOS | Il prompt reso dal chat template contiene già il token di inizio: `nativeBegin`/`nativeBeginVision` ora ricevono `addSpecial=false` quando il template è stato applicato (`LlamaEngine.buildPrompt` restituisce anche l'origine del prompt). |
| Testo dopo la fine del turno | `AnswerSanitizer` (nuovo, `llm`) taglia a `<|im_end|>`, `<|im_start|>`, `<|endoftext|>`, `<|eot_id|>`, `<|end|>`, `<end_of_turn>`, `</s>`, trattiene i suffissi parziali e ferma il loop nativo. Usato in chat, in BIG B Code e nel salvataggio file. |
| EOS/EOG | Resta `llama_vocab_is_eog` nel ciclo nativo; il sanitizer copre i modelli che non dichiarano il marker come EOG. |
| Template mancante | Fallback testuale conservato e segnalato. |
| Streaming | `StreamingBuffer` invariato (25 fps); il testo è filtrato dal sanitizer prima della UI. |

Test: `AnswerSanitizerTest` (5 test), incluso il caso di marker spezzato tra due chunk.

## 7. Revisione tecnica per area

### Kotlin / Compose

- ViewModel della chat ospitato in `AppRoot`: la conversazione non viene ricreata passando ai
  modelli e il drawer può avviare una nuova chat da qualsiasi schermata.
- `HistoryDrawer` non è più montato (sostituito da `AppDrawer` + `HistoryScreen`); le funzioni di
  cartella, preferiti, rinomina, archiviazione ed eliminazione sono state portate nella nuova
  schermata, quindi non è stata rimossa alcuna funzionalità.
- `HistoryScreen`: ricerca con debounce implicito (query → repository), cartelle, spostamento,
  eliminazione con codice di conferma.
- Nessuna nuova dipendenza: l'app continua a usare solo le librerie già presenti.

### C++ / JNI / llama.cpp

- Sessioni native registrate in un set protetto da mutex: `nativeCancel` non può dereferenziare un
  handle liberato (comportamento 1.2.0 mantenuto).
- `has_vision` non viene più azzerato dalle generazioni testuali (1.2.0 mantenuto).
- Prompt più grande del contesto rifiutato con errore esplicito.
- `llama_memory_clear` a ogni generazione.
- Timing da `llama_perf_context` (1.2.0 mantenuto).

### GGUF e memoria

- mmap automatico (`LLAMA_LOAD_MODE_AUTO`), nessun mlock forzato.
- Stima RAM = pesi × 1,18 + KV cache + 96 MB di buffer; il modello viene rifiutato se supera il
  budget, con messaggio che suggerisce un modello più piccolo.
- Nessuna modifica ai formati GGUF: l'app non li converte e non li modifica.

### Database, sicurezza, privacy

- Room + SQLCipher, passphrase casuale da 32 byte protetta da Android Keystore (invariato).
- Backup `.bkb`: PBKDF2-HMAC-SHA256 150.000 iterazioni + AES-256-GCM (invariato).
- Nessuna telemetria; rete solo per download modelli e ricerca web opzionale.
- Nessuna nuova permission nell'manifest.

### BIG B Code

- Aggiunto supporto `file://` in `ProjectFiles.root` per i progetti creati dall'app.
- Nuovo progetto automatico, rinomina file, esportazione e condivisione del file aperto.
- Analisi statica, patch con approvazione, Git locale e sandbox WebView invariati.
- L'interfaccia continua a dichiarare che non esiste un compilatore Java/C++ sul dispositivo.

### Build e CI

- `build-native.sh` compila tutte le ABI (`ABIS`, default tre) e strippa ogni `.so`.
- `package-debug-apk.sh` verifica la presenza di `libbigbai.so` **e** `libsqlcipher.so` per ogni
  ABI, stampa byte e MiB e **fallisce** se l'APK è sotto 100 MiB.
- Workflow: timeout 120 minuti, build delle tre ABI, test JVM, `:app:lintDebug`, `assembleDebug`,
  packaging e artifact `BIG-B-AI-Debug-APK` (APK + `.sha256` + `.txt`, 30 giorni).
- Versione: `1.3.0`, `versionCode` 4.

## 8. Misurazioni

| Metrica | Valore |
|---|---|
| Test JVM | **91, 0 falliti** (core 26, code 26, llm 23, app 16) |
| Lint debug | **0 errori**, 66 warning (54 `GradleDependency`, 6 `AndroidGradlePluginVersion`, 1 `ObsoleteSdkInt`, altri) |
| Copertura linee | **8,0%** (838/10.434) |
| Copertura branch | **5,4%** (395/7.253) |
| Copertura per modulo | `core/chat` 94,7%, `core/lang` 99,1%, `code` 33,7%, `llm` 23,5%, `app/files` 29,6% |
| APK | **119.987.886 byte / 114,43 MiB / 119,99 MB** |
| SHA-256 APK locale | `58f98efa4cef80328e5ac16b83c0758b1dcaeb921670bdc618afd6e014677d18` |
| Libreria nativa | 3 ABI, 4,18–6,61 MB ciascuna (stripped), hash verificato dentro l'APK |
| Build | `assembleDebug`, `jacocoTestReport`, `lintDebug` → BUILD SUCCESSFUL |

I warning residui sono di dipendenze/AGP non aggiornate (aggiornarle senza un dispositivo su cui
verificare la compatibilità sarebbe un rischio non necessario) e `ObsoleteSdkInt` su
`mipmap-anydpi-v26`, necessario ad AAPT2 per `<adaptive-icon>`.

## 9. Limiti di questa verifica

**NON VERIFICATO** (nessun dispositivo fisico o emulatore disponibile in questo ambiente):

- avvio dell'app, rendering reale delle schermate, navigazione toccando lo schermo;
- inferenza reale (testo e immagini), token/s, TTFT, RAM/CPU/GPU, temperatura, batteria;
- download di un modello completo da Hugging Face, SAF, SQLCipher/Keystore, sandbox WebView,
  notifiche e foreground service;
- comportamento delle tre ABI su hardware reale: le librerie compilano e sono presenti nell'APK,
  ma non sono state caricate su un dispositivo.

La copertura JVM è bassa e non sostituisce i test strumentali. I test introdotti verificano la
logica pura (stato della chat, nomi file, sanitizzazione, naming progetti), non l'integrazione
Android.

## 10. File toccati in questa release

Nuovi: `ui/AppDrawer.kt`, `ui/history/HistoryScreen.kt`, `ui/plugins/PluginsScreen.kt`,
`ui/about/AboutScreen.kt`, `ui/chat/ChatStateTransitions.kt`, `files/FileExport.kt`,
`llm/AnswerSanitizer.kt`, `code/ProjectNaming.kt`, i rispettivi test, questo documento.

Modificati: `AppRoot.kt`, `ChatScreen.kt`, `ChatComponents.kt`, `ChatViewModel.kt`,
`ModelManagerScreen.kt`, `ModelManagerViewModel.kt`, `CodeScreen.kt`, `CodeViewModel.kt`,
`SettingsScreen.kt`, `MemoryScreen.kt`, `ProjectFiles.kt`, `ModelStore.kt`, `LlamaEngine.kt`,
`NativeLlama.kt`, `bigbai_jni.cpp`, `CMakeLists.txt`, `build-native.sh`,
`package-debug-apk.sh`, `android.yml`, i due `build.gradle.kts`, `README.md`, `docs/*`.
