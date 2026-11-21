# BIG B AI 1.1.0 — Audit tecnico

Questo documento riporta ciò che è stato realmente analizzato, modificato e verificato in questa
iterazione. Non contiene stime presentate come misure: dove non è stato possibile eseguire una
verifica su dispositivo, è scritto esplicitamente.

## Sintesi

| Voce | Risultato |
|---|---|
| Bug critico della chat | **Corretto alla radice**, con test di regressione |
| Test unitari JVM | **39, tutti superati** (`:core` 21, `:code` 18) |
| Build debug | **`BUILD SUCCESSFUL`** |
| Libreria nativa | Ricompilata (arm64-v8a, CPU), 5.945.248 byte |
| APK debug | 92.815.874 byte (88,5 MiB), firma `CN=Android Debug` |
| SHA-256 APK | `c118030f529aed115b99e10da64b5ec891e9cc13ff936cae90c7388450a3764c` |
| Test su dispositivo | **Non eseguiti**: nessun device/emulatore disponibile |

## 1. Bug critico: la conversazione usciva dalla chat

### Causa reale

Il percorso era questo, in `ChatViewModel.send()` (1.0.0):

1. `send()` impostava `isGenerating = true` nello stato.
2. Chiamava `ensureConversation()`, che con `conversationId == 0` creava la conversazione e poi
   chiamava `openConversation(id)`.
3. `openConversation()` inizia con la guardia:
   `if (_state.value.isGenerating) { error(...); return }`.
4. La guardia scattava **sempre** sul primo messaggio, perché la generazione era già iniziata.
   L'id veniva restituito al chiamante ma **non veniva mai scritto nello stato**.
5. Al messaggio successivo `conversationId` era ancora `0`, quindi veniva creata una **seconda**
   conversazione e la lista messaggi veniva sostituita: per l'utente, la chat "usciva" dalla
   conversazione.

Non era un problema di Navigation Compose né di lifecycle: la route restava montata. Era una
violazione di identità: due operazioni diverse (possedere una conversazione e mostrarla)
condividevano la stessa funzione e la stessa guardia.

### Correzione

- Nuova classe pura `core/chat/ConversationSession.kt`: possiede l'id attivo e garantisce che
  `resolve`/`resolveDeferred` creino **al massimo una** conversazione per chat.
- `ensureConversation()` non passa più da `openConversation()`: crea (una volta), salva l'id,
  aggiorna lo stato e avvia la collezione dei messaggi. La guardia "non cambiare chat mentre
  generi" resta solo su `openConversation()`, dove è corretta.
- L'id e la bozza sono salvati in `SavedStateHandle`: rotazione e morte del processo non perdono
  la conversazione aperta né il testo digitato.
- `newChat()` azzera esplicitamente la sessione; `deleteConversation()` confronta la sessione e
  lo stato, non solo uno dei due.

### Test di regressione

`ConversationSessionTest` (6 test) verifica, tra l'altro:

- due invii consecutivi risolvono lo **stesso** id e creano **una sola** conversazione;
- lo stato `isGenerating` non influenza l'identità;
- un id non valido non viene accettato;
- `reset()` è l'unico modo per aprire una nuova chat.

## 2. Altri difetti individuati e corretti

| # | Difetto | Effetto | Correzione |
|---|---|---|---|
| 1 | `nativeBegin()` azzerava `session->vision` | Dopo il **primo messaggio di testo**, la modalità Vision falliva con "No multimodal projector" | Separato `has_vision` (capability, impostata al load) dallo stato della generazione; rimosso l'azzeramento |
| 2 | `LlamaEngine.load()` riusava il modello anche con thread/batch diversi | Cambiare thread o batch nelle impostazioni non aveva effetto fino al riavvio | Il confronto include `threads` e `batchSize`; `LoadedModel` espone `batchSize` |
| 3 | Budget RAM calcolato sulla memoria disponibile all'avvio | Modello rifiutato o accettato con dati vecchi di minuti | `DeviceProfile.budgetBytes(mode, availableNow)` e lettura live in `ensureModel()` |
| 4 | "RAM usata dall'app" era solo heap Java | Sottostimava di GB un modello GGUF mmap | `MemorySampler.current()` usa il **PSS** del processo (kernel), fallback heap |
| 5 | Decodifica bitmap e conversione RGB sul thread principale | Jank/ANR con foto grandi | `withContext(Dispatchers.Default)` |
| 6 | `installedAll()` faceva I/O su file nel main thread | Micro-blocchi all'apertura del selettore e al primo invio | `withContext(Dispatchers.IO)` |
| 7 | Streaming: `builder.toString()` a ogni token + stato nuovo a ogni token | Allocazioni O(n²) e ricomposizioni a frequenza token | `StreamingBuffer` con finestra di 40 ms (~25 fps); snapshot completi solo quando serve |
| 8 | Auto-scroll animato a ogni token | Animazioni continue annullate, lista strappata mentre si legge | Solo se l'utente è in fondo; pulsante "vai all'ultimo messaggio" quando non lo è |
| 9 | `onCleared()` non fermava il loop nativo | Decodifica residua dopo la chiusura della schermata | `engine.stop()` prima di cancellare i job |
| 10 | Errore prima del primo token perdeva il testo | Bozza persa (es. nessun modello installato) | `restoreDraft()`; se il modello non è multimodale, allegati e testo tornano nel composer |
| 11 | Nessun feedback su "nuova chat" durante la generazione | Il tap sembrava ignorato | Messaggio esplicito: interrompere prima |
| 12 | Prompt in lingue diverse da IT/EN diceva "Answer in English" | Risposte nella lingua sbagliata | Direttiva dinamica per IT/EN/ES/FR/DE/PT |
| 13 | Baseline profile aggiunto e non pacchettizzato in debug | Peso inutile e rischio di claim non veri | **Valutato e rimosso**: AGP non esegue `compileDebugArtProfile` nel grafo di `assembleDebug` e l'APK non contiene `assets/dexopt/baseline.prof` (verificato) |

## 3. Tutor di lingua (italiano → altre lingue)

Requisito: far "imparare" al modello lingue oltre l'inglese partendo dall'italiano.

**Cosa fa realmente l'app**

1. Fornisce al modello un glossario curato italiano → lingua scelta (inglese, spagnolo, francese,
   tedesco, portoghese) con pronuncia approssimativa e frase d'esempio.
2. Istruisce il modello a rispondere in italiano, insegnare la lingua e correggere gli errori.
3. Chiede al modello di emettere una riga machine-readable per ogni parola nuova:
   `Lingua:ES — gracias = grazie (esempio: gracias por tu ayuda)`.
4. L'app estrae quelle righe (`LanguageCoach.parseLexicon`), le deduplica per parola e le salva
   nel database cifrato, categoria `Lingua:<CODICE>` della Memoria.
5. Ogni prompt successivo reinietta il lessico personale accumulato.

**Cosa non fa, e non viene dichiarato**

- Non modifica i pesi del modello: il fine-tuning on-device di un GGUF da GB non è realistico su
  un telefono e non è stato implementato.
- Il glossario è un seme curato, non un dizionario completo.
- Il lessico cresce solo quando il modello rispetta il formato richiesto; se non lo fa, la
  conversazione funziona ma non salva nulla.
- La qualità delle traduzioni dipende dal modello installato.

Test: `LanguageCoachTest` (7 test) verifica glossari per tutte le lingue/livelli, contenuto del
prompt, filtro della lingua, deduplica e formato delle lezioni.

## 4. Interfaccia e usabilità

- **Design system** (`ui/theme/Theme.kt`): palette indigo/ciano, ruoli Material coerenti, tipografia
  con scala e spaziature definite, forme 8/12/16/22/28 dp, tema scuro rivisto.
- **Chat**: header con titolo reale della conversazione e stato del modello, bolle ridisegnate
  (utente pieno, assistente con bordo), streaming con cursore, pulsante stop distinto, composer
  arrotondato con allegati, stato vuoto con azioni, banner lessico, pulsante "torna in fondo".
- **Impostazioni**: card con bordo, nuova sezione Tutor di lingua, opzione "Riduci animazioni",
  testi esplicativi su cosa è reale e cosa no.
- **Memoria**: stato vuoto progettato, card con bordo, categorie e ricerca invariate.
- **Modelli**: nota di compatibilità RAM accanto al budget reale.
- **BIG B Code**: composer ridisegnato, card introduttiva, tab e diagnostica invariati nella
  sostanza (nessuna funzione rimossa).
- **Navigazione**: transizioni fade/slide brevi (0 ms con "Riduci animazioni"), `BackHandler` che
  chiude prima il drawer della cronologia.
- **Feedback**: aptica discreta su invio/stop, stati di caricamento, messaggi di errore con
  suggerimento per dispositivi da 4 GB.

## 5. Prestazioni e memoria

| Intervento | Perché | Verifica |
|---|---|---|
| Streaming a 40 ms | Meno allocazioni e ricomposizioni | Test JVM sul buffer; build |
| `key` + `contentType` + `animateItem` | Ricomposizioni mirate nella lista | Compilazione; non misurato su device |
| I/O modelli su `Dispatchers.IO` | Main thread libero | Codice; non misurato |
| Bitmap su `Dispatchers.Default` | Evita jank/ANR | Codice; non misurato |
| PSS invece di heap Java | Metrica RAM reale | Codice; non misurato |
| Budget RAM live | Meno fallimenti di caricamento | Codice; non misurato |
| Prompt limitato a 24 messaggi e a budget token | Finestra KV sotto controllo | Codice; non misurato |
| `LazyColumn` con chiavi stabili | Solo gli elementi visibili | Codice; non misurato |

**Paging**: valutato. La cronologia del prompt è già limitata; la lista UI è lazy e le query Room
sono indicizzate. Non è stato introdotto Paging 3 perché aggiungerebbe complessità senza un
problema misurato. Limite noto: una conversazione con migliaia di messaggi carica comunque tutte
le righe della conversazione aperta.

**Baseline Profiles**: valutati e non inclusi. Nella variante `debug` AGP non li pacchettizza
(verificato: task assente dal grafo e nessun `assets/dexopt/baseline.prof` nell'APK). Non viene
quindi dichiarato alcun guadagno.

## 6. Test

| Suite | Test | Copertura |
|---|---|---|
| `ConversationSessionTest` | 6 | Identità della chat, regressione del bug critico |
| `StreamingBufferTest` | 4 | Throttling e flush dello streaming |
| `LanguageCoachTest` | 7 | Glossari, prompt tutor, parsing/dedup del lessico |
| `HtmlTextTest` | 4 | Estrazione testo HTML |
| `PatchEngineTest` | 8 | Protocollo patch, diff, statistiche |
| `StaticAnalyzersTest` | 10 | HTML, CSS, Java, C++ |
| **Totale** | **39** | |

Comando eseguito:

```bash
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest assembleDebug
```

Non esistono test strumentali eseguiti: richiedono un dispositivo o un emulatore, non disponibili
in questo ambiente. Non viene quindi dichiarato verificato l'avvio dell'app, l'inferenza, il
download dei modelli, SAF, Keystore, SQLCipher, la sandbox WebView o le notifiche.

## 7. Build e consegna

```bash
bash scripts/fetch-llama.sh
bash scripts/build-native.sh
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest assembleDebug
bash scripts/package-debug-apk.sh
```

| Campo | Valore |
|---|---|
| APK | `dist/BIG-B-AI-debug-1.1.0.apk` |
| Dimensione | 92.815.874 byte (88,5 MiB) |
| SHA-256 | `c118030f529aed115b99e10da64b5ec891e9cc13ff936cae90c7388450a3764c` |
| Versione | 1.1.0-debug, versionCode 2 |
| Firma | `CN=Android Debug` |
| ABI | arm64-v8a |
| Nativo | `lib/arm64-v8a/libbigbai.so`, 5.945.248 byte |

L'APK è più grande del precedente (92.103.958 byte) per il nuovo codice, le risorse del tutor e la
libreria nativa ricompilata: **+711.916 byte**. Non è stato aggiunto peso fittizio.

La CI (`.github/workflows/android.yml`) non è stata modificata: continua a costruire il nativo,
eseguire i test JVM, produrre l'APK debug, verificare la presenza di `libbigbai.so`, calcolare lo
SHA-256 e caricare l'artifact **BIG-B-AI-Debug-APK** (retention 30 giorni).

## 8. Limiti residui

1. **Nessuna verifica runtime**: avvio, inferenza, Vision, download, SAF, Keystore, WebView e
   notifiche restano non eseguiti su dispositivo.
2. **Solo arm64-v8a, Android 12+, CPU**: nessuna GPU/Vulkan, nessun modello incluso.
3. **Nessun OCR**: i PDF scansionati restano non leggibili; il messaggio lo dichiara.
4. **Nessun compilatore Java/C++** sul dispositivo: BIG B Code fa analisi statica reale, non
   compilazione.
5. **Tutor linguistico in-context**: nessun addestramento dei pesi; il lessico dipende dal formato
   emesso dal modello.
6. **Paging non introdotto**: conversazioni enormi caricano tutte le righe della chat aperta.
7. **Dispositivi da 4 GB**: il budget e i messaggi di rifiuto sono stati migliorati, ma la
   compatibilità reale non è stata misurata su hardware.
8. **Nessun test strumentale**: le suite Android non sono state eseguite.
