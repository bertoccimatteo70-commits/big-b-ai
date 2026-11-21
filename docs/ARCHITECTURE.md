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
- **code** — dominio di BIG B Code: file, analisi, patch, Git, sandbox.
- **app** — Compose, navigazione, ViewModel, servizi Android, onboarding.

La dependency injection è manuale (`AppContainer`): un singleton creato in `BigBAiApplication`
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
- `ModelDownloadManager` scarica con `Range` (ripresa), scrive su file temporaneo, verifica
  SHA-256 e sposta atomicamente il file nella posizione definitiva.
- `ModelDownloadService` è un foreground service `dataSync` con notifiche di avanzamento.

## Prompt e memoria

`PromptTemplates` compone il messaggio di sistema con: lingua, regole di risposta, memoria
dell'utente (solo se abilitata), fonti e istruzioni sugli strumenti. La cronologia recente viene
inclusa entro un budget di token; i messaggi più vecchi restano nel database ma non nel prompt.

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
magic "BIGBAI1" | versione | salt(16) | iv(12) | ciphertext | tag GCM
```

La chiave deriva dalla password con PBKDF2-HMAC-SHA256 (150.000 iterazioni, 256 bit). Il payload è
JSON compresso con chat, messaggi, ricordi e (opzionalmente) progetti. Il ripristino è transazionale
e salta i record già presenti (deduplica per hash). Le chiavi Keystore sono legate al dispositivo,
quindi il backup non le usa: per questo serve una password.

## BIG B Code

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

## UI

Compose Material 3 con design system indigo/ciano, tipografia e forme condivise, light/dark/system.
Navigazione con `NavHost` e cinque route: chat, modelli, memoria, impostazioni, codice, con
transizioni brevi disattivabili ("Riduci animazioni"). I ViewModel sono creati da
`AppViewModelFactory` (la chat usa `SavedStateHandle` per ripristinare id e bozza); lo stato è
esposto con `StateFlow` e collezionato con `collectAsState`. Il pulsante Indietro chiude prima il
drawer della cronologia.

L'onboarding (5 schermate) configura tema, lingua, profilo RAM, primo modello e memoria.

## Misurazione e I/O

`MemorySampler` legge il PSS del processo (non il solo heap Java), quindi la RAM mostrata include
pesi mmap e allocazioni native. Il budget RAM viene ricalcolato sulla memoria disponibile al
momento del caricamento, non su uno snapshot di avvio. Le operazioni su file dei modelli girano su
`Dispatchers.IO`; la decodifica delle immagini per la Vision su `Dispatchers.Default`.

I Baseline Profiles sono stati valutati e non inclusi: AGP non li pacchettizza nella variante
debug usata per la consegna (verificato nell'APK).
