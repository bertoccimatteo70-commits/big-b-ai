# Architettura

## Moduli

Il progetto è diviso in quattro moduli Gradle con dipendenze unidirezionali:

```
app  ->  code  ->  core
 |        |
 +------> llm ---+
```

- **core** — modello dati, persistenza, sicurezza, servizi di dominio. Non conosce la UI.
- **llm** — motore di inferenza e gestione dei modelli. Contiene il codice C++ e il catalogo.
- **code** — dominio di ITAGPT Code: file, analisi, patch, Git, sandbox.
- **app** — Compose, navigazione, ViewModel, servizi Android, onboarding.

La dependency injection è manuale (`AppContainer`): un singleton creato in `ItaGptApplication`
espone repository, motore, impostazioni e servizi. Niente Hilt, niente reflection, tempi di
avvio prevedibili.

## Persistenza

Room su SQLCipher. All'avvio:

1. `KeystoreSecret` genera (prima volta) 32 byte casuali e li cifra con una chiave AES-GCM
   non esportabile di Android Keystore.
2. I byte in chiaro diventano la passphrase di SQLCipher e vengono azzerati dopo l'apertura.
3. `SupportOpenHelperFactory` apre `bigbai.db` con cifratura full-file.

Entità: `folders`, `conversations`, `messages`, `memory_categories`, `memories`, `projects`,
`project_memory`, `project_messages`, `code_changes`, `downloads`.

### Identità della conversazione

`ConversationSession` (in `core/chat`) possiede l'id della chat aperta, separatamente dallo stato
UI. `resolve`/`resolveDeferred` creano una conversazione **solo** quando nessuna è attiva; la
funzione che la mostra (`openConversation`) resta distinta e mantiene la guardia "non cambiare
chat mentre generi". Questa separazione è la correzione alla radice del bug 1.0.0 in cui il primo
messaggio non registrava l'id e il secondo apriva una nuova conversazione. L'id e la bozza sono
salvati in `SavedStateHandle`.

`StreamingBuffer` accumula i token e pubblica uno snapshot al massimo ogni 40 ms: la UI resta
fluida senza ricomporre e allocare a ogni token.

Allegati e fonti sono serializzati in JSON nelle colonne `attachmentsJson` / `sourcesJson`
tramite `TypeConverter`; il resto è relazionale.

## Motore di inferenza

`LlamaEngine` possiede un dispatcher a parallelismo 1 (`Dispatchers.Default.limitedParallelism(1)`):
una sola generazione alla volta, per costruzione.

- `load()` valida il budget RAM, carica il modello (e il proiettore visivo se presente) e pubblica
  `EngineState.Ready` con le capability reali lette dal nativo.
- `generate()` consegna i token a un callback `onToken` eseguito sul dispatcher di inferenza; la
  decodifica UTF-8 è incrementale, così i caratteri multibyte non si spezzano tra un token e
  l'altro. La UI li accumula in `StreamingBuffer` e pubblica stati a frequenza limitata.
- `stop()` imposta un flag atomico e cancella la sessione nativa: la generazione termina al primo
  token successivo, senza terminare il processo.
- Le metriche (token/s, tempo al primo token, RAM) sono misurate, non stimate.

### Ponte JNI

`llm/src/main/cpp/bigbai_jni.cpp`:

- Registro di sessioni con handle opaco e lock: una cancellazione concorrente non può liberare
  memoria mentre il thread di decodifica la usa.
- Testo: prompt formattato con il template della chat, campionamento top-k/top-p/temperatura.
- Visione: `libmtmd` con immagini RGB; il percorso è attivo solo se il modello ha un `mmproj`.
- `LLAMA_LOAD_MODE_AUTO`: mmap quando possibile, fallback a read.
- CPU only, `arm64-v8a`, `libbigbai.so` costruita da `scripts/build-native.sh`.

llama.cpp è fissato al tag **v1.0.0** (commit `b1b770d90d94cad9e9ea5b993bdbc383a522fb28`) e non
è vendorizzato: `scripts/fetch-llama.sh` lo scarica e verifica il commit.

## Modelli

`model_catalog.json` (asset del modulo `llm`) descrive famiglie, varianti, file, dimensioni e
SHA-256. Il catalogo è stato verificato contro l'API di Hugging Face (hash LFS reali).

- `ModelStore` gestisce installazione, file parziali, spazio libero e record installati.
- `BundledChatModel` e `BundledModelInstaller` copiano dagli asset il modello di chat incluso e
  l'embedder, verificano dimensione e SHA-256 e scrivono il marker di installazione. Il modello di
  chat è Qwen2.5 1.5B Instruct Q4_K_M; senza di esso la chat non parte, non c'è un fallback finto.
- `ModelDownloadManager` scarica con `Range` (ripresa), scrive su file temporaneo, verifica
  SHA-256 e sposta atomicamente il file nella posizione definitiva.
- `ModelDownloadService` è un foreground service `dataSync` con notifiche di avanzamento.

## OCR e documenti

- `OcrEngine` usa ML Kit Latin Text Recognition (`com.google.mlkit:text-recognition`) sul
  dispositivo. Le immagini vengono ridimensionate, ruotate secondo EXIF e passate al recognizer;
  i PDF senza testo vengono renderizzati pagina per pagina con `PdfRenderer` (massimo 20 pagine).
- Il testo OCR viene mostrato in un'anteprima con copia/inserimento e aggiunto al contesto del
  messaggio, senza inviare l'immagine a un server.
- `DocumentTextExtractor` (modulo `core`) legge DOCX e XLSX come archivi ZIP/XML, CSV e TXT come
  testo; `.doc`/`.xls` binari sono rifiutati con un messaggio esplicito. I limiti sono applicati
  prima della lettura in memoria.

## Prompt e memoria

`PromptTemplates` compone il messaggio di sistema con: lingua, regole di risposta, memoria
dell'utente (solo se abilitata), testo OCR/documenti allegato, fonti e istruzioni sugli strumenti.
Per i modelli piccoli usa una variante compatta, perché elenchi di regole troppo lunghi peggiorano
l'aderenza. La cronologia recente viene inclusa entro un budget di token; i messaggi più vecchi
restano nel database ma non nel prompt.

La memoria è scritta solo dopo conferma esplicita dell'utente: nessun fatto viene salvato
automaticamente. Eccezione dichiarata: con il tutor di lingua attivo, le righe `Lingua:XX` che il
modello produce vengono salvate nel lessico della categoria `Lingua:<CODICE>` senza una seconda
conferma, perché sono il meccanismo con cui la lezione successiva ricorda i vocaboli. L'impostazione
si disattiva dalle Impostazioni.

### Tutor di lingua

`LanguageCoach` (in `core/lang`) contiene i glossari curati italiano → inglese/spagnolo/francese/
tedesco/portoghese, costruisce la sezione di sistema del tutor e analizza le righe machine-readable
prodotte dal modello. Il lessico personale è letto da Room a ogni prompt: è apprendimento
in-context persistente, non addestramento dei pesi.

## Backup

Formato `.bkb`:

```
magic "ITAGPT1" | versione | salt(16) | iv(12) | ciphertext | tag GCM
```

La chiave deriva dalla password con PBKDF2-HMAC-SHA256 (150.000 iterazioni, 256 bit). Il payload è
JSON compresso con chat, messaggi, ricordi e (opzionalmente) progetti. Il ripristino è transazionale
e salta i record già presenti (deduplica per hash). Le chiavi Keystore sono legate al dispositivo,
quindi il backup non le usa: per questo serve una password.

## ITAGPT Code

- **ProjectFiles** — wrapper su `DocumentFile` con path relativi, lettura/scrittura testuale,
  snapshot ricorsivo e limiti di dimensione. Nessun path assoluto, nessuna risalita oltre la radice.
- **StaticAnalyzers** — regole deterministiche per HTML (annidamento tag, doctype, script, alt),
  CSS (bilanciamento blocchi), Java (bilanciamento ignorando stringhe/commenti, nome classe
  pubblica, catch vuoti), C++ (bilanciamento, include guard, `using namespace` negli header).
  Non è un compilatore e non finge di esserlo.
- **PatchEngine** — protocollo testuale `<<<<<<< FILE: path ... >>>>>>>` (e `DELETE:`), più diff
  unificato compatto per l'approvazione. Il parsing è line-oriented perché i modelli piccoli
  sbagliano il JSON con il codice.
- **ProjectRepository** — progetti, messaggi, memoria, registro delle modifiche applicate.
- **GitManager** — JGit su un mirror privato nella storage dell'app: commit di snapshot, log,
  diff, ripristino, branch. Il ripristino riscrive i file nella cartella autorizzata via SAF.
- **JsSandbox** — WebView usa-e-getta con JavaScript abilitato, accesso file disabilitato, rete
  intercettata e bloccata salvo autorizzazione, timeout e raccolta di log/errori.

## Chat: modifica, rigenerazione, export

Una modifica utente passa da `ChatRepository.editUserMessage`: aggiorna il testo e cancella in una
sola transazione logica tutte le righe con id successivo nella stessa conversazione. Il ViewModel
chiama poi `startSend(Reuse(...))`, che riusa il messaggio già persistito invece di inserirne un
secondo. La rigenerazione elimina le risposte dopo l'ultimo messaggio utente e riparte dallo stesso
testo; il prompt legge la cronologia dal database escludendo il messaggio riusato, quindi il modello
non vede la risposta che sta sostituendo.

`FileExport` produce Markdown, testo semplice e un PDF reale con `android.graphics.pdf.PdfDocument`:
impaginazione a righe misurate dal `Paint`, più pagine, numero di pagina e nessuna dipendenza
esterna. Il salvataggio usa MediaStore in `Download/ITAGPT`; la condivisione usa il FileProvider.

## Voce

`SpeechPlayer` incapsula `TextToSpeech` del sistema: inizializzazione asincrona, accodamento di una sola frase prima della connessione, fallback alla lingua di sistema se il locale richiesto non è
installato, stop esplicito e `shutdown` nel `DisposableEffect`. La lingua (`auto`/`it`/`en`) e la
lettura automatica delle nuove risposte sono impostazioni persistenti. La dettatura usa
`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`: l'app non dichiara il permesso microfono e non registra
audio; il recognizer di sistema restituisce il testo e l'utente decide se inviarlo. Non esiste
ascolto continuo né conversazione full-duplex.

## UI

Compose Material 3 con design system ITAGPT indigo/ciano, tipografia e forme condivise,
light/dark/system. Navigazione con `NavHost` e le route chat, cronologia, ricerca, modelli (libreria,
download, installati), memoria, impostazioni, codice, diagnostica, plugin e informazioni; le
transizioni sono disattivabili ("Riduci animazioni"). La scala del testo è un'impostazione
persistita e viene applicata con `LocalDensity` sopra l'intero albero Compose. I ViewModel sono
creati da `AppViewModelFactory` (la chat usa `SavedStateHandle` per ripristinare id e bozza); lo
stato è esposto con `StateFlow` e collezionato con `collectAsState`. Il pulsante Indietro chiude
prima il drawer della cronologia.

L'onboarding (5 schermate) configura tema, lingua, profilo RAM, modello incluso e memoria. Il
modello di chat viene estratto dagli asset con avanzamento e verifica SHA-256, non scaricato.

## Misurazione e I/O

`MemorySampler` legge il PSS del processo (non il solo heap Java), quindi la RAM mostrata include
pesi mmap e allocazioni native. Il budget RAM viene ricalcolato sulla memoria disponibile al
momento del caricamento, non su uno snapshot di avvio. Le operazioni su file dei modelli girano su
`Dispatchers.IO`; la decodifica delle immagini per la Vision su `Dispatchers.Default`.

I Baseline Profiles sono stati valutati e non inclusi: AGP non li pacchettizza nella variante
debug usata per la consegna (verificato nell'APK).
