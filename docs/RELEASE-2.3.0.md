# ITAGPT 2.3.0 — ITAGPT Code con strumenti reali

Data: 22 novembre 2026. Commit del codice: `120f020a3287d94d3b0b80c6b71c0765e66f1f3a`. Workflow:
**Android CI - ITAGPT debug and release**. Run: [#45025333599](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/45025333599), conclusa con successo.

Questa consegna parte da ITAGPT 2.2.0 e interviene **solo** sulle funzionalità mancanti o non
verificate di ITAGPT Code. Ciò che era già corretto (eliminazione con conferma, limiti di 40 file e
72 operazioni, selezione del modello, percorsi, checkpoint) è stato mantenuto e, dove serviva,
esteso; non è stato riscritto senza motivo.

Come sempre: ciò che non è stato eseguito su un dispositivo non viene dichiarato verificato.

## 1. Cosa è stato aggiunto

### 1.1 Compilazione ed esecuzione: cosa è realmente possibile

| Linguaggio | Analisi statica | Validazione dati | Esecuzione | Compilazione |
|---|---|---|---|---|
| HTML | sì (tag, doctype, script) | — | **sì**, WebView isolata | non applicabile |
| JavaScript | — | — | **sì**, WebView isolata | non applicabile |
| JSON | sì (errore con riga e colonna) | **sì**, parser completo | — | non applicabile |
| XML/SVG/PLIST | sì | **sì**, correttezza formale | — | non applicabile |
| CSV/TSV | sì | **sì**, righe/colonne/virgolette | — | non applicabile |
| Java | sì (parentesi, nome classe, catch vuoti) | — | **no** | **no**, javac non esiste su Android |
| Kotlin | sì (parentesi, `TODO()`, catch vuoti, `.kts`) | — | **no** | **no**, kotlinc non esiste su Android |
| C/C++ | sì (parentesi, include guard, include locali) | — | **no** | **no**, clang non è disponibile per le app |
| Python | sì (parentesi, `except:`, indentazione mista) | — | **no** | **no**, interprete non incluso |

La differenza tra i livelli non è una scritta nella UI: `LanguageSupport` è la stessa tabella usata
dal terminale, dal verificatore, dal prompt dell'agente e dalla schermata Strumenti.

**Perché non è stato aggiunto un compilatore.** Un `javac`/`kotlinc`/`clang` utilizzabile su Android
richiede una toolchain di decine o centinaia di MB, con classi Java non presenti nel runtime Android
e problemi di memoria su dispositivi da 4 GB. Avrebbe peggiorato l'app senza dare una build
affidabile. La scelta dichiarata è: esecuzione reale dove Android la consente (WebView), validazione
reale per i formati dati, analisi statica trasparente per i linguaggi compilati, nessuna simulazione.

### 1.2 Terminale integrato

`ProjectTerminal` (`code/.../ProjectTerminal.kt`) è un interprete di comandi che opera **solo** sulla
cartella del progetto, attraverso `ProjectWorkspace`. Non è una shell di sistema e non ci prova.

- **Comandi**: `pwd`, `cd`, `ls [-l]`, `tree`, `find`, `cat`, `head`, `tail`, `wc`, `grep [-i] [-n]`,
  `stat`, `sha256`, `mkdir`, `touch`, `cp`, `mv`, `rm [-r]`, `echo` con `>` e `>>`, `run`, `verify`,
  `git status|log|diff|branch`, `support`, `history`, `clear`, `date`, `help`.
- **Directory di lavoro**: relativa al progetto; `/` è la radice del progetto, non la radice Android.
- **Cronologia**: fino a 50 comandi, navigabile con freccia su/giù nella tastiera fisica.
- **Errori**: messaggi espliciti, codice di uscita 0/1, nessun risultato inventato.
- **Interruzione**: il pulsante ferma il comando in corso; `run` annulla anche la WebView.
- **Limiti**: 400 caratteri per comando, 16 argomenti, 20.000 caratteri di output, 15 s per comando
  (60 s per `run`/`verify`/`git`), 200 KB per file letto, 300 file scanditi.
- **Permessi**: gli stessi della cartella scelta dall'utente (SAF); nessun permesso nuovo, nessun
  accesso a `/system`, `/data` o ad altre app.
- **Isolamento**: `ProjectPaths` rifiuta percorsi assoluti, `..`, segmenti nascosti e caratteri di
  controllo; non esistono pipe, sottoprocessi o espansioni di shell.

### 1.3 Verifica reale dei progetti

`ProjectVerifier` produce un report in cui ogni controllo ha una categoria e uno stato:

- `STATIC_ANALYSIS` — `StaticAnalyzers` su tutti i file analizzabili.
- `DATA_VALIDATION` — `JsonValidator`, `XmlValidator`, `CsvValidator`: parser reali, non regex.
- `EXECUTION` — esecuzione del file di ingresso HTML/JavaScript nella sandbox, con log, errori,
  timeout e richieste di rete bloccate.
- `COMPILATION` — presente solo per dichiarare **non disponibile** la compilazione, con il motivo.
- `TESTS` — conteggio dei file di test riconosciuti, senza fingere di averli eseguiti.

Stati: `PASSED`, `WARNING`, `FAILED`, `UNSUPPORTED`, `INFO`. Il riepilogo dice quanti controlli sono
stati eseguiti e quanti **non** lo sono stati. Lo stesso report è usato da:

1. il pulsante «Verifica ora» nella scheda Strumenti;
2. il comando `verify` del terminale;
3. la verifica automatica dopo ogni attività dell'agente, prima di dichiarare il risultato.

Il prompt dell'agente continua a vietare di inventare compilazioni e test; ora l'app ha dati reali
per smentirlo.

### 1.4 Agente testabile e test d'integrazione

Il ciclo dell'agente è stato estratto in `AgentRunner` (Kotlin puro, senza Android e senza il modulo
`llm`). L'app fornisce modello, workspace, checkpoint e callback; il runner esegue gli strumenti e
restituisce un `AgentOutcome` con file scritti, checkpoint, motivo del limite, timeout e conferme
richieste.

I nuovi test d'integrazione (`CodeEnvironmentIntegrationTest`) eseguono **codice reale** su un
workspace in memoria:

- flusso completo richiesta → scrittura → verifica → esecuzione → lettura dal terminale;
- modifica simultanea di più file;
- provider che rifiuta la scrittura (nessun falso successo);
- timeout che lascia il progetto intatto;
- rollback con ripristino verificato byte per byte;
- modifica dell'utente dopo l'attività, che il rollback non tocca;
- progetto grande con limiti applicati;
- modello non disponibile;
- terminale e agente sullo stesso progetto.

I doppi di test sono solo il modello linguistico e il runtime WebView, che non possono girare su
JVM. Workspace, agente, terminale, verificatore e rollback sono il codice di produzione.

### 1.5 Rollback e recupero

Il rollback esistente è stato reso più sicuro, senza ricostruirlo:

- `RollbackPlanner` calcola esattamente i file da ripristinare e quelli da eliminare;
- un file il cui contenuto non è più quello scritto dall'attività viene **lasciato intatto** e
  segnalato come modifica dell'utente;
- dopo il ripristino i byte vengono riletti e confrontati con il checkpoint
  (`RollbackGuard.verifyRestore`); un ripristino parziale viene dichiarato, non nascosto;
- il rollback automatico è **opt-in** e richiede tutte le condizioni: verifica fallita, almeno un
  file scritto, checkpoint disponibile, nessuna modifica dell'utente;
- prima di ogni ripristino viene creato un checkpoint di sicurezza, quindi anche il rollback è
  reversibile;
- i limiti del checkpoint (600 file / 12 MB) restano espliciti.

### 1.6 Sandbox: cosa è stato verificato

- rete disabilitata per default; con rete autorizzata restano bloccati loopback, `10.*`,
  `172.16–31.*`, `192.168.*`, `169.254.*`, multicast, `.local`, IPv6 loopback/ULA/link-local e
  **IPv4 mappati in IPv6** (`::ffff:192.168.0.1`), correzione introdotta in 2.3;
- ogni richiesta passa da `shouldInterceptRequest`; la navigazione principale fuori da
  `sandbox.local` è bloccata;
- timeout di 8 s, errori JavaScript e promise rifiutate raccolti, `WebView.destroy()` a fine corsa;
- file, content provider, popup, storage e mixed content disabilitati.

La policy è testata; la WebView **non** è stata eseguita su un dispositivo in questa consegna.

### 1.7 Prestazioni e dimensioni

- Il progetto viene letto una volta per attività, non a ogni passo.
- Le letture sono limitate per file, per numero di file e per caratteri totali.
- L'analisi dell'editor resta ritardata di 250 ms e fuori dal thread principale.
- Il terminale limita output, file scanditi e tempo per comando.
- Il progetto non viene copiato in cache: una cache SAF potrebbe servire contenuti vecchi dopo una
  modifica esterna, quindi è stata evitata.
- **Nessuna dipendenza nuova**: terminale, validatori, verificatore e rollback sono codice del
  progetto. L'APK non contiene modelli AI.

## 2. File principali

| File | Ruolo |
|---|---|
| `code/.../Workspace.kt` | interfaccia del progetto e path policy condivisa |
| `code/.../ProjectTerminal.kt` | interprete dei comandi, limiti, cronologia |
| `code/.../SafWorkspace.kt` | adattatore SAF per terminale e agente |
| `code/.../ProjectVerifier.kt` | verifica a categorie e report |
| `code/.../Validators.kt` | parser JSON, XML, CSV |
| `code/.../AgentRunner.kt` | ciclo dell'agente testabile |
| `code/.../RollbackGuard.kt` | impronte, piano di ripristino, verifica |
| `code/.../ExecutionRuntime.kt` | contratto di esecuzione e matrice dei linguaggi |
| `app/.../ui/code/CodeViewModel.kt` | stato, terminale, verifica, rollback |
| `app/.../ui/code/CodeScreen.kt` | scheda Terminale, verifica, tabella linguaggi |

## 3. Test eseguiti

| Modulo | Test | Esito |
|---|---|---|
| `core` | 60 | 0 falliti |
| `code` | 171 | 0 falliti |
| `llm` | 108 | 0 falliti |
| `app` | 25 | 0 falliti |
| **Totale JVM** | **364** | **0 falliti, 0 errori, 0 saltati** |

Nuove suite: `ProjectTerminalTest` (22), `ValidatorsTest` (18), `ProjectVerifierTest` (11),
`AgentRunnerTest` (17), `RollbackGuardTest` (7), `LanguageAnalyzersTest` (12),
`CodeEnvironmentIntegrationTest` (10).

Lint debug: **0 errori, 70 warning** (gli stessi warning preesistenti su API deprecate e
localizzazione). Non esistono test strumentali: non c'era un dispositivo o emulatore.

I conteggi qui sopra sono quelli dei file XML prodotti dalla run CI #45025333599, non una stima.

## 4. APK

| APK | Dimensione | SHA-256 | Firma |
|---|---|---|---|
| `ITAGPT-debug-2.3.0.apk` | 150.010.223 byte (143,06 MiB) | `82bd914fc7843352f542c9c9aa935ac5198a085de6cfb2770b9c74bd7252c3a6` | debug |
| `ITAGPT-release-debug-signed-2.3.0.apk` | 80.389.559 byte (76,67 MiB) | `df9a043bc250123c7dc367a531ce56615e3d949b80d5cbf5b02efe4d87d90435` | chiave debug, **non ufficiale** |

Artifact della run (ZIP): [debug 59.604.663 byte](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/45025333599/artifacts/14755674175),
[release 40.368.035 byte](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/45025333599/artifacts/14755812502),
[report CI 66.006 byte](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/45025333599/artifacts/14756400092).
Gli SHA-256 sono stati ricalcolati localmente sui file scaricati dagli artifact (`sha256sum -c`: OK).
Entrambi gli APK sono universali (`arm64-v8a`, `armeabi-v7a`, `x86_64`), non contengono file `.gguf`
e hanno firma valida; la release usa la chiave debug, non quella ufficiale.

## 5. Stato reale

| Requisito | Stato |
|---|---|
| Terminale reale con comandi documentati | **COMPLETATO E VERIFICATO** con 22 test JVM; non provato su SAF reale |
| Cronologia, interruzione, timeout, limiti | **COMPLETATO E VERIFICATO** con test JVM |
| Distinzione analisi / validazione / esecuzione / compilazione | **COMPLETATO E VERIFICATO** con test JVM |
| Esecuzione HTML/JavaScript | **IMPLEMENTATO NON VERIFICATO SU DEVICE** (WebView non eseguita) |
| Validazione JSON/XML/CSV | **COMPLETATO E VERIFICATO** con parser reali e test |
| Compilazione Java/Kotlin/C/C++ | **NON DISPONIBILE**, dichiarata all'utente |
| Verifica automatica dopo l'attività dell'agente | **COMPLETATO E VERIFICATO** con test d'integrazione |
| Rollback con modifiche utente preservate | **COMPLETATO E VERIFICATO** con test JVM |
| Ripristino verificato byte per byte | **COMPLETATO E VERIFICATO** con test JVM |
| Rollback automatico | **COMPLETATO E VERIFICATO** come policy; non eseguito su device |
| Test d'integrazione dell'ambiente | **COMPLETATO E VERIFICATO** su JVM |
| Tablet, mouse, trackpad, tastiera fisica | **NON VERIFICATO**: tab scrollabili e frecce nel terminale, nessun hardware |
| Sandbox isolata e sicura | **PARZIALE**: policy testata, WebView non eseguita su device |
| Prestazioni su progetti grandi | **PARZIALE**: limiti e letture misurati in test, nessun benchmark su device |

## 6. Limiti residui

1. Nessun dispositivo fisico o emulatore: SAF, JGit, WebView, rendering, mouse e tastiera non sono
   stati eseguiti su Android.
2. Compilazione assente per Java, Kotlin, C/C++ e Python: è una limite della piattaforma, non una
   scelta di comodo, ed è dichiarata in UI, prompt, documento e report di verifica.
3. La sandbox è isolata secondo la policy, ma non è stata sottoposta a penetration test su device.
4. Il checkpoint Git copre fino a 600 file / 12 MB; oltre, il ripristino può essere incompleto e
   viene segnalato.
5. La qualità delle risposte del modello Code non è stata misurata in questa consegna.
6. La firma ufficiale della release richiede i quattro secret del keystore, ancora assenti.
