# Audit ITAGPT 1.5.1

Versione di partenza: 1.5.0. Questo audit riguarda solo ciò che è stato realmente modificato,
eseguito e misurato. Dove una verifica non era possibile nell'ambiente, è dichiarato.

## 1. Stabilità del motore semantico

### Causa individuata

`EmbeddingEngine.embed()` leggeva l'handle nativo (`session.handle`) **fuori** dal lock e poi
lo passava a `nativeEmbed`. Tra la lettura e la chiamata nativa, `unload()` poteva acquisire il
lock, liberare il modello e azzerare l'handle: la chiamata nativa usava memoria già liberata
(use-after-free). Lo stesso schema era presente tra caricamento e inferenza.

### Correzione

La gestione del ciclo di vita è stata spostata in `EmbedderSession`, Kotlin puro con le
operazioni native iniettate:

- **un solo `Mutex`** protegge caricamento, embedding, scaricamento e `reload`;
- l'handle viene letto e usato **dentro** la stessa sezione critica: non può essere liberato
  mentre un'inferenza è in corso;
- `close()` attende l'embedding in corso e libera una sola volta (idempotente);
- un embedding dopo `close()` ricarica il modello;
- un caricamento rifiutato dalla policy RAM non chiama il loader nativo;
- gli errori nativi sono riportati in `failure()` e azzerati dal successo successivo.

`EmbeddingEngine` è ora un wrapper sottile che inietta le funzioni native e la policy RAM.

### Test

`EmbedderSessionTest` (8 test) copre: 8 embedding concorrenti con un solo caricamento,
`close()` che attende l'inferenza in corso e non libera sotto di essa, ricaricamento dopo
chiusura, doppia chiusura, rifiuto RAM senza chiamata nativa, errore di caricamento con retry,
errore di embedding azzerato dal successo, `reload` atomico.

## 2. Persistenza dell'indice semantico

### Problema

Il salvataggio poteva fallire senza che il chiamante lo sapesse: l'indice in memoria veniva
sostituito comunque e l'utente vedeva un messaggio di successo.

### Correzione

`SemanticIndexStore`:

1. scrive su `index.bin.part`;
2. esegue `fd.sync()`;
3. **rilegge** il file e confronta numero e contenuto delle entry;
4. copia l'indice precedente in `index.bin.bak`;
5. sposta il `.part` sul file principale con `ATOMIC_MOVE`;
6. propaga un `Result` con l'errore reale.

Al caricamento, un file corrotto viene spostato in `index.bin.corrupt`; se esiste un backup
valido viene ripristinato e riscritto, altrimenti l'indice riparte vuoto e lo stato è
segnalato. `SemanticRepository` applica le modifiche a una **copia** e sostituisce l'indice in
memoria solo dopo un salvataggio verificato; in caso di errore restituisce
`IndexOutcome(persisted = false, error = …)` e la UI non mostra più una conferma falsa.

### Test

`SemanticIndexStoreTest` (6) copre roundtrip, assenza del `.part`, recupero dal backup con
quarantena, corruzione senza backup, fallimento di scrittura che non tocca l'indice precedente,
indice vuoto valido distinto da corrotto, `clear`.
`SemanticRepositoryTest` (8) copre indicizzazione e riavvio, salvataggio fallito senza
sostituzione, retrieval sopra soglia, memorie (aggiunta/rimozione/pulizia), ricostruzione,
embedder non disponibile, recupero da corruzione, `clear`.
`SemanticIndexTest` copre `copy()` indipendente e `readFromOrNull` che rifiuta un file troncato.

## 3. SHA-256 e marker di installazione

Il marker precedente conteneva solo l'hash: se il file veniva sostituito o troncato dopo
l'installazione, il marker restava e il modello era considerato valido. Il nuovo
`InstallMarker` v2 ha formato `FORMAT|sha256|size|mtime`:

- accettato solo se hash, dimensione e mtime coincidono con il file;
- un marker legacy (solo hash) viene ignorato e si esegue un hash reale una volta;
- mtime diverso (file sostituito o modificato) forza un hash reale;
- la verifica completa resta disponibile su richiesta;
- la scrittura del marker è atomica (`.part` + move).

`InstallMarkerTest` (6) copre roundtrip, rifiuto del marker legacy, marker malformati, mtime
cambiato, dimensione cambiata e i dati dichiarati del modello.

## 4. Quality gate e risposte troncate

### Problema

`shouldRetry` non distingueva una risposta tagliata dal limite di token da una terminata con
EOS: il testo che non finiva con un punto veniva trattato come troncato, e il retry poteva
ripetere una generazione già completa. Non c'era un limite esplicito ai tentativi.

### Correzione

- Il motore nativo espone `finish_reason` (0 sconosciuto, 1 EOS, 2 lunghezza, 3 annullato) via
  JNI; `LlamaEngine` lo mappa in `FinishReason` dentro `GenerationMetrics`.
- `QualityGate.shouldRetry` ritenta **solo** `LENGTH`, loop (`repetitionRatio > 0,45`) o code
  fence non chiuso. Mai EOS, `CANCELLED`, `TIMEOUT`, `ERROR`, risposta vuota o oltre 90 s.
- `MAX_ATTEMPTS = 1`: una sola riscrittura.
- Su `LENGTH` il retry riceve `min(maxTokens + 512, 4096)` token; sugli altri casi temperatura
  più bassa e repeat penalty più alta.
- `isAcceptableRetry` rifiuta una riscrittura troncata quando la prima era completa, o molto
  più ripetitiva, o con un fence aperto che prima era chiuso: la prima risposta viene
  conservata e solo annotata.
- `ChatViewModel` conserva `firstResult`/`firstText`/`firstQuality` e li ripristina se il retry
  fallisce, è annullato, va in timeout o è peggiore. Nessun ciclo possibile.

### Test

`QualityGateTest` (16) copre loop, fence, risposta normale, vuota, `LENGTH` con testo
apparentemente completo, EOS con euristica di troncamento, annullamento/timeout/errore, limite
di un tentativo, budget del retry, descrizione e i quattro criteri di accettazione.

## 5. Qualità dell'italiano

Non è stata dichiarata alcuna superiorità linguistica: la 1.5.1 non cambia il modello né il
sampler di default. Sono stati verificati i punti che possono rompere l'italiano a
prescindere dal modello:

| Elemento | Stato |
|---|---|
| Tokenizer | Il modello e5 usa SentencePiece; il testo italiano è passato senza normalizzazioni distruttive. |
| Chat template | Applicato dal GGUF tramite llama.cpp; Qwen2.5 usa ChatML, nessun template hardcoded. |
| EOS/EOG | `llama_vocab_is_eog` ferma la generazione; il motivo è ora esposto e distinto da `LENGTH`. |
| UTF-8 | I token parziali sono accumulati in `StreamingBuffer` e decodificati solo su sequenze complete. |
| Sampler | top-k 40, top-p 0,9, temperatura da profilo, repeat penalty; nessun campionamento greedy nascosto. |
| Quantizzazione | Q2–Q6 dichiarate nel catalogo; Q4_K_M è il default, non un valore imposto. |
| Base/Instruct | Il catalogo contiene solo Instruct per la chat; i modelli base non sono offerti come chat. |
| Contesto | Il contesto viene ridotto con la cronologia; il taglio non spezza i token (tokenizza il prompt finale). |

### Prova reale su modello

Sono stati scaricati dal catalogo e verificati con SHA-256 due modelli reali, poi eseguiti con
lo stesso motore llama.cpp su host x86_64, template ChatML e gli stessi controlli dell'app:

| Modello | Byte | SHA-256 | Esito |
|---|---:|---|---|
| Qwen2.5 0.5B Instruct Q4_K_M | 491.400.032 | `74a4da8c…a9db` | 8 prompt italiani: 6 EOS, 2 LENGTH; errori grammaticali reali |
| Qwen2.5 1.5B Instruct Q4_K_M | 1.117.320.736 | `6a1a2eb6…407e` | 8 prompt italiani; vedi report |

I report completi con prompt e risposte sono in `docs/ai-eval/`. Il modello 0,5B sbaglia
davvero («qual'è» descritto come forma corretta, risposta sul raffreddore che ignora il testo,
loop su una frase): sono limiti del modello, non del motore, e non vengono nascosti dai
controlli automatici. Il 1.5B è migliore ma resta un modello piccolo. **Nessun confronto
1.5.0/1.5.1 sulla qualità linguistica è possibile**: il modello e il prompt non sono cambiati,
quindi non viene inventato un miglioramento.

## 6. Retrieval

Soglia misurata con dataset italiano (10 documenti, 18 domande) sul modello incluso:
a 0,80 precision 0,769 e 3 falsi positivi; a 0,82–0,83 precision 1,000 e recall 1,000.
`MIN_SCORE` è passato a **0,83**. Dettagli e tabella completa in `docs/RETRIEVAL-1.5.1.md`.
Il dataset è piccolo e sintetico: la soglia va rimisurata su un corpus reale prima di
considerarla definitiva per un dominio.

## 7. RAM

La soglia di 550 MiB della 1.5.0 era arbitraria. `EmbeddingMemoryPolicy` calcola:

| Componente | Byte |
|---|---:|
| Modello e5-small Q8_0 | 132.439.008 |
| Runtime (contesto, buffer, thread) | 48 MiB |
| Margine di sicurezza | 56 MiB |
| **Totale base** | **236.439.008 byte (≈ 225,5 MiB)** |
| Dispositivo low-RAM | +48 MiB |
| Pressione memoria ≥ 2 | +56 MiB |
| Pressione 3 (critica) | rifiuto esplicito |

Quindi un dispositivo con 400 MiB liberi può caricare l'embedder, mentre la 1.5.0 lo
rifiutava. Il caricamento resta su richiesta, lo scaricamento è esplicito e il motore non
viene caricato se la policy rifiuta. `EmbeddingMemoryPolicyTest` (7) verifica soglie, extra,
rifiuto a un byte in meno e rifiuto a pressione critica.

## 8. ITAGPT Code

Nessuna modifica funzionale in 1.5.1. Il modulo `:code` mantiene la distinzione già introdotta
nella 1.5.0 tra analisi statica, compilazione (solo se il toolchain è presente) ed esecuzione;
non è stato aggiunto alcun compilatore fittizio. I 48 test del modulo passano. Non è stato
possibile eseguire un progetto reale su dispositivo: nessun emulatore disponibile.

## 9. Documentazione corretta

- `docs/LIMITATIONS.md`: non afferma più che l'APK non include modelli; distingue il modello
  di embedding incluso (MIT) dai modelli di chat scaricati.
- `docs/AUDIT-1.5.md`: aggiunta la tabella APK / ZIP artifact / build locale / APK 1.4 con i
  byte reali; chiarito che il requisito di 200 MiB si applica all'APK.
- `docs/MODELS.md`: sezione del modello di embedding con licenza MIT, SHA-256, ruolo e RAM.
- `BundledModel.kt`: il KDoc dichiara MIT, verificato su
  `https://huggingface.co/api/models/TwinSunsLLC/multilingual-e5-small-gguf`
  (`cardData.license: "mit"`, base `intfloat/multilingual-e5-small`, MIT). Non è stata
  modificata alcuna licenza.

## 10. Test e copertura

| Modulo | Test | Esito |
|---|---:|---|
| `:core` | 42 | 0 falliti |
| `:code` | 48 | 0 falliti |
| `:llm` | 106 | 0 falliti |
| `:app` | 16 | 0 falliti |
| **Totale** | **212** | **0 falliti** |

Comando eseguito:

```bash
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest \
  :llm:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug jacocoTestReport
```

Lint: 0 errori. Copertura JaCoCo misurata: **16,0%** delle linee (2.127 coperte, 11.202
mancanti), contro l'8,0% misurato nella 1.3.0. L'obiettivo del 30% **non è raggiunto**: il codice non coperto è soprattutto UI
Compose, Room, PDF e backup, che richiedono un dispositivo/emulatore o Robolectric, non
disponibili in questo ambiente. Le nuove logiche critiche sono coperte molto più della media:

| Classe | Copertura linee |
|---|---:|
| `EmbeddingMemoryPolicy` | 100% |
| `QualityGate` | 100% |
| `InstallMarker` | 100% |
| `SemanticIndex` | 95% |
| `EmbedderSession` | 93% |
| `SemanticIndexStore` | 91% |
| `Embeddings` | 89% |
| `SemanticRepository` | 95% |

Non sono stati creati test finti per alzare la percentuale.

## 11. Interfaccia

Nessuna modifica grafica in 1.5.1: bolle bianche `#FFFFFF` con testo nero, blocchi codice
monospaziati e spaziature restano quelli della 1.5.0. Le uniche modifiche UI sono testuali:
note di troncamento/riscrittura nella chat, stato di persistenza e pulsante di ricostruzione
in Diagnostica. **Non verificata visivamente**: nessun dispositivo o emulatore disponibile.

## 12. APK e pipeline

APK scaricato dall'artifact della run CI
[44907676537](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44907676537)
(commit `ced8116`) e verificato dopo il download:

| Voce | Valore |
|---|---|
| File | `ITAGPT-debug-1.5.1.apk` |
| Versione | 1.5.1-debug, versionCode 7 |
| Dimensione APK | **248.141.501 byte (236,65 MiB)** |
| Requisito 200 MiB | soddisfatto (209.715.200 richiesti) |
| SHA-256 APK | `3935bbedf2b035e90b4cda5f9a531b679f18b7b448677060170f537609098ada` |
| ABI | arm64-v8a, armeabi-v7a, x86_64 |
| Firma | valida (`CN=Android Debug`), `apksigner` exit 0 |
| Manifest | `com.bigbai.app.debug`, versionCode 7, minSdk 31, targetSdk 35 |
| Modello incluso | `assets/models/multilingual-e5-small-q8_0.gguf`, SHA-256 `e011debc…877c93` |
| Artifact ZIP | 167.725.123 byte, SHA-256 `2522dc75…3488` |

La build locale ha la stessa dimensione (248.141.501 byte) ma SHA-256
`ef820ffa…36f6`: cambiano i timestamp di firma, non il contenuto funzionale.
Il workflow `android.yml` esegue test, lint, build nativa delle tre ABI, `assembleDebug`,
packaging, verifica di firma/manifest/ABI/modello e fallisce se l'APK è sotto 200 MiB.
L'artifact si chiama `ITAGPT-Debug-APK` ed è conservato 30 giorni.
