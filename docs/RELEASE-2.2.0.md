# ITAGPT 2.2.0 — aggiornamento ITAGPT Code

Data: 21 novembre 2026. Commit del codice: `816d68d`. Workflow:
**Android CI - ITAGPT debug and release**. Run:
[#44961030062](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44961030062),
conclusa con successo.

Questo documento riguarda l'intervento tecnico su ITAGPT Code. La consegna 2.1.0 (rimozione dei
modelli, ricerca Internet, adattamento tablet) resta descritta in
[RELEASE-2.1.0.md](RELEASE-2.1.0.md). Come sempre, ciò che non è stato eseguito su un dispositivo
non viene dichiarato verificato.

## 1. Correzioni P0

### 1.1 Eliminazione con conferma

- L'esplora file non elimina più al primo tocco: `deleteFile` mette l'elemento in
  `pendingManualDelete` e apre una finestra di conferma.
- La finestra mostra il nome e distingue **file** da **cartella**; per le cartelle avverte che
  viene rimosso tutto il contenuto e la cancellazione percorre l'albero, perché molti provider
  SAF rifiutano di eliminare una cartella non vuota in un solo colpo.
- Se il file aperto è dentro la cartella eliminata, l'editor viene chiuso e non resta su un file
  inesistente.
- Le eliminazioni richieste dall'agente mantengono la conferma separata già esistente.
- Il percorso passa dalla stessa policy di validazione dell'agente.

### 1.2 Limite reale dei file

`EditBudget` (`code/.../ProjectPaths.kt`) applica:

- **40 file distinti** per attività: riscrivere lo stesso file non consuma un secondo slot;
- **72 operazioni** totali (12 passi × 6 azioni), quindi il limite non è aggirabile dividendo il
  lavoro in più passaggi;
- blocco prima della scrittura, non dopo: il quarantunesimo file non viene toccato;
- messaggio esplicito nell'activity log e nella chat, con il motivo del blocco;
- il prompt dell'agente dichiara entrambi i limiti e vieta di aggirarli.

Il limite di 200.000 byte per file resta applicato nel parser, prima di qualsiasi scrittura.

### 1.3 Un solo modello Code

`CodeModelResolver` è l'unica fonte per etichetta e caricamento:

- un modello Code scelto esplicitamente viene usato solo se è ancora installato e completo;
- se il modello scelto è stato rimosso, l'app mostra un errore e **non** carica silenziosamente il
  modello Chat;
- senza scelta esplicita vince il miglior modello con ruolo `CODE`;
- il modello Chat è usato solo se non esiste alcun modello Code, ed è etichettato «modello chat»;
- l'etichetta mostrata e il modello caricato provengono dalla stessa scelta;
- nessun modello installato produce un messaggio chiaro, non un caricamento infinito.

### 1.4 Percorsi

`ProjectPaths` è condiviso da agente, esplora file, rinomina, eliminazione e checkpoint:

- rifiuta percorsi assoluti, `C:\`, segmenti `.` e `..`, segmenti nascosti, caratteri di controllo
  e nomi troppo lunghi;
- **accetta** nomi innocui come `notes..final.txt` e `src/app..min.js`;
- una radice `file://` è ammessa solo dentro `projects` dell'app: una riga corrotta del database non
  può esporre storage arbitrario;
- scrittura verificata rileggendo il contenuto; se il provider tronca il file, viene ripristinato il
  testo precedente e l'operazione fallisce;
- una sostituzione viene rifiutata se il vecchio contenuto non è leggibile, per non distruggere un
  file che non si potrebbe recuperare;
- rinomina rifiutata se esiste già un elemento con quel nome.

### 1.5 Checkpoint e rollback

- Il checkpoint viene creato **prima** della prima scrittura. Se fallisce, nessun file viene
  modificato e l'utente riceve il motivo.
- Un progetto vuoto produce comunque un commit baseline (`allowEmpty`).
- L'eliminazione richiesta dall'agente crea il checkpoint prima di eliminare, non dopo.
- `rollbackTask` ripristina i file presenti nel checkpoint e rimuove solo i file creati
  dall'attività; i file non toccati restano.
- Prima del ripristino viene creato un checkpoint di sicurezza, quindi il rollback è a sua volta
  reversibile.
- Il rollback richiede conferma e riporta file ripristinati, rimossi e non ripristinati.
- Se il progetto o il commit non sono leggibili, il ripristino viene annullato senza modificare
  nulla.

Limite noto: il checkpoint fotografa i file di testo fino a 600 file / 12 MB; un progetto più
grande può avere parti non incluse. Il limite è esplicito, non silenzioso.

### 1.6 Timeout, interruzione e stato

- `stopGeneration` ferma il motore e il ciclo controlla la cancellazione prima di ogni passo e
  prima di ogni scrittura.
- `onCleared` chiama `engine.stop()` oltre a cancellare la coroutine: chiudere la schermata non
  lascia la generazione nativa attiva.
- Timeout per passo invariato a 240 s; allo scadere il motore viene fermato.
- Se l'attività viene interrotta dopo modifiche, il messaggio indica quanti file sono stati
  modificati e lo short id del checkpoint disponibile.
- Se il modello restituisce testo vuoto, ripetitivo o un blocco non chiuso, non viene applicata
  alcuna modifica e l'errore è visibile.

## 2. Miglioramenti P1

### 2.1 Contesto del progetto

`ProjectContextBuilder` sostituisce «i primi file disponibili» con un punteggio:

- corrispondenza tra richiesta e nome/contenuto del file;
- priorità a entry point (`index.html`, `main.*`, `README`, manifest, build file);
- boost ai file importati dai file più pertinenti;
- limite per file (6.000 caratteri) e totale (24.000), con tetto di 6 MB letti;
- il contesto viene letto una volta per attività, non a ogni passo.

### 2.2 Editor

- numeri di riga;
- evidenziazione di commenti, stringhe, numeri e keyword senza alterare gli offset;
- ricerca nel file, sostituzione singola e sostituzione globale;
- undo/redo con storia locale;
- larghezza calcolata sulla riga più lunga e scorrimento orizzontale, per non spezzare il codice;
- indicatore di riga/colonna e numero di righe;
- indicatore delle modifiche non salvate già presente;
- analisi statica ritardata di 250 ms e fuori dal thread principale durante la digitazione.

### 2.3 Tastiera e tablet

Scorciatoie nell'editor: `Ctrl+S` salva, `Ctrl+F` cerca, `Ctrl+Z` annulla, `Ctrl+Y` ripete,
`Ctrl+A` seleziona tutto. I controlli restano raggiungibili con Tab; il layout usa le stesse classi
di dimensione della 2.1. Non sono stati eseguiti test su tablet, mouse o trackpad reali.

### 2.4 Verifica reale e sandbox

- L'analisi statica è etichettata come tale e non viene presentata come compilazione.
- HTML/JavaScript viene eseguito in una WebView usa-e-getta con timeout.
- La sandbox blocca rete esterna salvo autorizzazione, e **anche con rete autorizzata** blocca
  loopback, `10.*`, `192.168.*`, `172.16–31.*`, `169.254.*`, `.local`, IPv6 loopback/ULA/link-local
  e IPv4 mappati in IPv6. I nomi pubblici che iniziano per `fc`/`fd` non vengono confusi con IPv6.
- La navigazione del frame principale fuori da `sandbox.local` viene bloccata.
- Accesso file, content provider, popup, storage e mixed content sono disabilitati.
- Java/Kotlin/C++ non vengono eseguiti: l'app dice esplicitamente che serve una toolchain non
  inclusa in Android.

## 3. Test

Nuovi test JVM:

| Suite | Copertura |
|---|---|
| `ProjectPathsTest` | traversal, assoluti, doppi punti innocui, rinomina, prefissi |
| `EditBudgetTest` | 40 file, stesso file, tetto operazioni, aggiramento su più passi |
| `CodeModelResolverTest` | modello esplicito, modello mancante, Code automatico, fallback chat |
| `ProjectContextBuilderTest` | pertinenza, import, budget caratteri, stop word |
| `SandboxPolicyTest` | rete, host privati, navigazione, asset locali |

Totale dopo l'intervento: **266 test JVM, 0 falliti, 0 errori, 0 saltati**. Lint:
**0 errori, 69 warning**. I test strumentali e su dispositivo restano
assenti.

## 4. APK

| APK | Dimensione | SHA-256 | Firma |
|---|---|---|---|
| `ITAGPT-debug-2.2.0.apk` | 149.698.927 byte (142,76 MiB) | `40064adafa740def47ddf403fa7b5c5636a2c44ddb659f9a8faddf6a7dbaacc3` | debug |
| `ITAGPT-release-debug-signed-2.2.0.apk` | 80.274.871 byte (76,56 MiB) | `fb48aabadb43d88afe14e85f61383635c6026e8089f75e13b74048f3bfc28ede` | debug, non ufficiale |

Artifact:

- [Debug](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44961030062/artifacts/14730385186) — ZIP 59.475.051 byte;
- [Release](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44961030062/artifacts/14730350297) — ZIP 40.311.813 byte;
- [Report CI](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44961030062/artifacts/14730240743) — ZIP 60.397 byte, con XML dei test e lint.

La release è firmata con la chiave debug del runner: non è una release ufficiale e il certificato
può cambiare tra run.

## 5. Stato reale

| Requisito | Stato |
|---|---|
| Eliminazione manuale con conferma | **IMPLEMENTATO NON VERIFICATO SU DEVICE** |
| Limite 40 file e 72 operazioni | **COMPLETATO E VERIFICATO** con test JVM |
| Modello Code coerente | **COMPLETATO E VERIFICATO** con test JVM |
| Percorsi e scrittura verificata | **COMPLETATO E VERIFICATO** per la logica pura; SAF non provata |
| Checkpoint prima delle modifiche | **IMPLEMENTATO NON VERIFICATO SU DEVICE** (JGit non testato su Android) |
| Rollback del task | **IMPLEMENTATO NON VERIFICATO SU DEVICE** |
| Timeout e cancellazione | **PARZIALE**: logica e stop del motore; nessun device |
| Contesto pertinente | **COMPLETATO E VERIFICATO** con test JVM |
| Editor, ricerca, undo/redo, scorciatoie | **IMPLEMENTATO NON VERIFICATO SU DEVICE** |
| Sandbox e isolamento rete | **COMPLETATO E VERIFICATO** per la policy; WebView non eseguita |
| Test completi dell'agente con modello | **NON IMPLEMENTATO**: richiede un modello e SAF reali |
| Mouse, trackpad, tablet | **NON VERIFICATO** |

## 6. Limiti residui

1. Nessun dispositivo o emulatore: conferme, editor, WebView, SAF e JGit su Android non sono stati
   eseguiti.
2. Il limite del checkpoint (600 file / 12 MB) può escludere progetti molto grandi.
3. Il rollback non ripristina file binari o file esclusi dallo snapshot.
4. Non esiste un terminale o un compilatore Android: Java/Kotlin/C++ restano analisi statica.
5. La firma ufficiale richiede i quattro secret del keystore, ancora assenti.
6. Il modello Code reale non è stato caricato: i test coprono la selezione, non la generazione.
