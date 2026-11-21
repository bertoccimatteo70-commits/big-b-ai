# BIG B AI 1.5.0 — Audit: retrieval locale, precisione, BIG B Code e APK da 200 MiB

Data: 21 novembre 2026. Versione `1.5.0` (versionCode 6), variante debug
`com.bigbai.app.debug`.

Questo documento descrive cosa è stato cambiato nel codice, cosa è stato misurato e
cosa **non** è stato possibile verificare in questo ambiente. Le affermazioni non
verificate sono indicate come tali. Il requisito di consegna era un APK universale
di almeno 200 MiB: la sezione 7 riporta la misura reale, non una stima.

---

## 1. Sintesi

| Area | 1.4.0 | 1.5.0 |
|---|---|---|
| Test JVM | 124 | **165** (+41), 0 falliti |
| Modello di embedding nell'APK | assente | **multilingual-e5-small Q8_0** (132.439.008 byte) |
| Ricerca nel contesto | solo memoria recente e PDF corrente | **retrieval semantico** su documenti e memorie, filtrato per conversazione |
| Controlli deterministici | nessuno | calcolatrice verificata + controllo italiano + quality gate con una riscrittura |
| BIG B Code | agente con 5 strumenti | + analisi multi-file, duplicati, dipendenze mancanti, report modifiche, sandbox automatica, test |
| Diagnostica | assente | schermata con motore, RAM, CPU, metriche, integrità del modello, indice |
| APK debug | 115.341.878 byte | **248.660.875 byte (237,14 MiB)** |
| Lint | 0 errori, 66 warning | 0 errori, 66 warning |

---

## 2. Audit del repository

### Cosa è stato controllato

- **Architettura**: quattro moduli Gradle (`app`, `core`, `llm`, `code`), 18.791 righe
  Kotlin/C++ nelle sorgenti principali. Nessun modulo orfano, nessuna dipendenza
  circolare.
- **Dipendenze**: nessuna nuova dipendenza di runtime in 1.5. Il modello di embedding
  è un file, non una libreria. Il motore nativo resta llama.cpp al commit fissato.
- **Segreti**: nessuna chiave API, password o token nel codice o nelle risorse.
- **SQL**: nessuna query grezza (`rawQuery`/`execSQL`); Room genera le query
  parametrizzate e SQLCipher cifra il database.
- **Log**: nessun `println` nelle sorgenti principali.
- **Rete**: nessun endpoint `http://` in chiaro; il download dei modelli usa HTTPS e
  la sandbox blocca ogni richiesta remota salvo autorizzazione.
- **WebView**: JavaScript attivo, accesso a file e content provider disattivato,
  storage disattivato, rete bloccata sia con `blockNetworkLoads` sia in
  `shouldInterceptRequest`, un solo bridge (`BigBAIConsole.log/error`), `destroy()`
  su completamento, timeout e annullamento.
- **Permessi**: sei, tutti usati (INTERNET, ACCESS_NETWORK_STATE,
  POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK).
  Nessun permesso di storage, fotocamera, contatti o posizione: i file passano dal
  SAF e dal `FileProvider`.
- **Backup**: disattivato a livello Android (`allowBackup=false`,
  `dataExtractionRules`); il backup esplicito `.bkb` è cifrato con
  PBKDF2-HMAC-SHA256 (150.000 iterazioni) + AES-256-GCM.
- **Integrità dei modelli**: SHA-256 obbligatorio al termine del download; il modello
  di embedding incluso è verificato tre volte (script di fetch, controllo
  nell'APK, controllo a runtime prima dell'uso).
- **Percorsi**: l'agente rifiuta percorsi assoluti, `..` e file nascosti; la
  scrittura resta confinata alla cartella autorizzata.

### Problemi trovati e corretti

1. **Il primo modello di embedding candidato non si caricava.** Il GGUF
   `cstr/multilingual-e5-small` produceva `bert model needs to define token type
   count` con llama.cpp alla revisione usata. Un secondo candidato caricava ma
   dava similarità 0,95 tra testi non correlati. È stato scelto e validato
   `TwinSunsLLC/multilingual-e5-small-gguf` (Q8_0), con misure reali riportate
   nella sezione 3. Il modello è fissato per dimensione e SHA-256.
2. **Chunk con sovrapposizione oltre il limite.** `Embeddings.chunk` poteva
   superare `maxChars` quando la coda del chunk precedente era lunga; corretto e
   coperto da test.
3. **Duplicati persi a fine blocco.** Il rilevatore scartava le finestre che
   contenevano una parentesi di chiusura, perdendo funzioni duplicate; corretto e
   coperto da test.
4. **`package.json` ignorato dall'analisi.** Il file non era considerato “codice”,
   quindi le dipendenze importate risultavano sempre non dichiarate; corretto.
5. **Vettori orfani alla cancellazione di una memoria.** Eliminare un ricordo
   lasciava i suoi passaggi nell'indice; ora `delete` e `clearAll` rimuovono anche
   i vettori (le memorie vengono indicizzate alla creazione e all'aggiornamento).
6. **Testo del PDF duplicato nel prompt.** Con il retrieval attivo il PDF veniva
   inviato sia per intero sia come passaggi; ora il testo completo è limitato a
   4.000 caratteri quando ci sono passaggi recuperati.
7. **Riscrittura senza limite.** Il controllo di qualità poteva riscrivere in loop;
   ora la riscrittura è una sola, solo se la ripetizione supera 0,45 o un fence
   resta aperto, e solo entro 90 secondi.
8. **Embedder mai rilasciato.** Sotto pressione di memoria il modello di embedding
   restava caricato; ora `onTrimMemory`/`onLowMemory` lo scaricano e il retrieval
   torna lessicale finché c'è spazio.
9. **Sampler non configurabile.** `repeat_penalty` era fisso a 1,12 e `min_p` non
   esisteva; ora sono parametri nativi, con valori per famiglia di modello e
   regolazione manuale nelle impostazioni avanzate.
10. **Soglia di retrieval troppo bassa.** La soglia iniziale (0,62) avrebbe
    iniettato passaggi non pertinenti; portata a 0,80 dopo le misure della
    sezione 3.

---

## 3. Precisione dell'AI

### Retrieval semantico locale

- Modello: **multilingual-e5-small**, quantizzazione Q8_0, 384 dimensioni, finestra
  512 token, pooling medio, vettori normalizzati L2.
- Prefissi corretti per il modello: `query: ` per la domanda, `passage: ` per i
  documenti.
- Suddivisione in blocchi da 700 caratteri con sovrapposizione di 120.
- Similarità coseno, soglia **0,80**, massimo 6 passaggi per risposta.
- L'indice è persistente nella cartella privata dell'app; ogni conversazione vede
  solo i propri documenti più le memorie globali. I documenti vengono indicizzati
  al momento dell'invio, con l'id reale della conversazione.
- Il contesto recuperato entra nel prompt come “Passaggi pertinenti”, separato dal
  testo integrale del documento.

**Misure reali sul modello (host x86_64, `llama-embedding`):**

| Coppia | Similarità coseno |
|---|---|
| Frasi correlate (stesso argomento, parafrasi) | 0,907 / 0,908 |
| Frasi non correlate | 0,706 / 0,767 |

La soglia 0,80 sta sopra tutte le coppie non correlate misurate e sotto le coppie
correlate. Il margine però non è ampio (0,033 sopra il falso positivo peggiore):
documenti formulati in modo molto diverso dalla domanda possono non essere
recuperati. È una scelta deliberata a favore della precisione: meglio nessun
passaggio che un passaggio fuorviante. **Non è una valutazione su dispositivo**:
velocità e qualità su telefono non sono state misurate.

### Controlli deterministici

- **Calcolatrice** (`Calculator`): parser aritmetico con `+ - * / % ^`, parentesi,
  meno unario e decimali; ignora intervalli di anni e orari. Il risultato verificato
  viene mostrato e iniettato nel prompt, così il modello commenta un numero che non
  ha inventato.
- **Controllo italiano** (`ItalianCheck`): spazi multipli, spazio prima della
  punteggiatura, punteggiatura senza spazio, parole duplicate, virgolette e
  parentesi non bilanciate, frasi troppo lunghe. Ignora il codice. Non è un
  correttore grammaticale e non riscrive la risposta.
- **Quality gate**: misura le ripetizioni (6-gram) e i fence non chiusi; una sola
  riscrittura, con nota visibile all'utente quando avviene.

### Prompt

Il system prompt della chat ora impone, in italiano:

- concordanze, accenti, punteggiatura e nessuna parola inventata;
- distinzione tra ciò che è certo e ciò che è incerto;
- divieto di citare fonti, misure o risultati non forniti;
- uso esclusivo dei passaggi recuperati quando presenti, senza estenderli;
- gestione esplicita delle richieste ambigue;
- risposta diretta e non ripetitiva, con lunghezza proporzionata.

### Test

I test JVM coprono chunking, similarità, soglia, persistenza dell'indice, quality
gate, calcolatrice, controllo italiano, profili dei modelli e iniezione del contesto
nel prompt. **Non** è stata eseguita una valutazione con un modello di chat reale:
nessun modello è installato in questo ambiente e non esiste un dispositivo. Non
viene quindi dichiarato alcun punteggio di grammatica, comprensione o coerenza.

---

## 4. Motore di inferenza

- JNI esteso con un **embedder separato** (`nativeLoadEmbedder`, `nativeEmbed`,
  `nativeEmbedderDim`, `nativeFreeEmbedder`), così il modello di embedding non
  occupa il contesto del modello di chat e può essere scaricato da solo.
- Sampler: `repeat_penalty`, `min_p`, `seed` (-1 = casuale) oltre ai parametri
  esistenti; i profili per famiglia scelgono valori diversi per modelli piccoli e
  grandi.
- Nessuna GPU: il backend Vulkan resta escluso (mancano shader SPIR-V verificati).
  Tutta l'inferenza è su CPU.
- Le tre ABI sono compilate e strippate: `libbigbai.so` 5.951.120 byte (arm64-v8a),
  4.195.768 (armeabi-v7a), 6.620.424 (x86_64).
- Il modello di embedding viene copiato una volta dagli asset alla memoria privata
  e verificato con SHA-256; l'APK lo conserva non compresso (`noCompress += "gguf"`)
  per evitare una decompressione da 132 MB al primo avvio.

---

## 5. BIG B Code

- **Analisi multi-file** (`ProjectInsights`): fino a 200 file e 600.000 caratteri per
  analisi; rileva blocchi duplicati (finestre di 8 righe normalizzate), dipendenze
  importate ma non dichiarate (`package.json`, `requirements.txt`,
  `pyproject.toml`), `TODO`/`FIXME`, file molto grandi e pattern a rischio.
- **Report delle modifiche**: dopo le scritture dell'agente elenca file creati,
  modificati ed eliminati, con righe aggiunte/rimosse e l'esito dei controlli.
- **Verifica automatica**: se il progetto contiene HTML, dopo una scrittura viene
  eseguita la sandbox e il risultato (log, errori, richieste bloccate) entra nel
  report. Non viene mai dichiarata una compilazione: sul dispositivo non esistono
  `javac`/`clang`.
- **Generazione di test**: richiesta dedicata che produce test per il codice del
  progetto, con l'elenco dei file coinvolti.
- **Azioni rapide** nella chat di Code: revisione del codice, spiegazione della
  struttura, analisi del progetto.
- Restano i limiti della 1.4: 5 passi per attività, 40 file distinti, 200.000 byte
  per file, conferma obbligatoria per le eliminazioni, checkpoint Git prima della
  prima scrittura.

---

## 6. Nuove funzioni e interfaccia

- **Diagnostica**: versione del motore, ABI, modello attivo, RAM di processo,
  core CPU, token/s e tempo al primo token dell'ultima generazione, stato del
  modello di embedding (presente, dimensione, SHA-256), numero di passaggi
  indicizzati, ultimo errore del motore.
- **Memoria semantica**: i ricordi sono indicizzati e recuperati per similarità;
  l'utente può disattivarla nelle impostazioni; cancellare un ricordo cancella i
  suoi vettori.
- **Indicatore di contesto**: la barra della chat mostra quanti passaggi locali
  sono stati usati per l'ultima risposta.
- **Impostazioni**: repetition penalty e min-p regolabili con impostazioni avanzate;
  interruttore della memoria semantica.
- **Interfaccia**: nuova voce “Diagnostica” nel menu, azioni rapide in BIG B Code,
  nota di qualità sotto la risposta quando il controllo interviene. Le bolle della
  chat restano bianche `#FFFFFF` con testo nero `#000000`, angoli arrotondati e
  codice monospaziato; il font resta Tinos.
- **Nuova chat**: continua a partire da uno stato vuoto e indipendente (messaggi,
  bozza, allegati, errori, metriche e passaggi recuperati non vengono ereditati).

Il rifacimento grafico è **incrementale**, non un redesign completo: senza un
dispositivo non è stato possibile verificare a schermo spaziature, contrasto reale,
animazioni e resa del font. Non viene dichiarato un risultato visivo.

---

## 7. APK universale

| Campo | Valore |
|---|---|
| File | `BIG-B-AI-debug-1.5.0.apk` |
| Versione | 1.5.0-debug (versionCode 6) |
| Dimensione | **248.075.965 byte (236,58 MiB)** |
| Requisito 200 MiB | **soddisfatto** (209.715.200 byte) |
| SHA-256 | `23a47f3be138c6742da27392eb7ddd9f1b6d64a75419a10b6df00023ca05e064` |
| ABI | arm64-v8a, armeabi-v7a, x86_64 |
| Firma | valida, chiave debug Android (`CN=Android Debug`) |
| Manifest | `com.bigbai.app.debug`, versionCode 6, versionName 1.5.0-debug |
| Modello incluso | `assets/models/multilingual-e5-small-q8_0.gguf`, 132.439.008 byte, SHA-256 `e011debc…77c93` |
| Build | `assembleDebug` + `scripts/package-debug-apk.sh` (CI) |
| Run CI | [44904141926](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44904141926), commit `f27d723` |

Composizione: dei 132,7 MB aggiunti rispetto alla 1.4 (115.341.878 byte), **132,4 MB sono il
modello di embedding**; il resto è codice e dex in più. Non c'è padding, nessun file duplicato,
nessuna risorsa casuale.

`scripts/package-debug-apk.sh` ora:

1. rifiuta l'APK se manca `libbigbai.so` o `libsqlcipher.so` in una delle tre ABI;
2. rifiuta l'APK se il modello di embedding manca o non ha dimensione e SHA-256 attesi;
3. verifica firma (`apksigner`) e manifest (`aapt2`: package, versionCode,
   versionName, ABI dichiarate);
4. confronta l'hash della copia in `dist/` con quello dell'APK appena compilato;
5. **fallisce se l'APK è sotto 209.715.200 byte**;
6. scrive `.sha256` e un file `.txt` con tutti i dati della build.

I valori qui sopra sono l'APK **scaricato dall'artifact** della run CI e ricalcolati dopo il
download: dimensione, SHA-256, firma, manifest, ABI e SHA-256 del modello dentro l'APK. La build
locale di verifica aveva una dimensione leggermente diversa (248.660.875 byte): gli stessi
sorgenti, ordinamento del dex e timestamp di firma diversi.

---

## 8. Test e verifiche eseguite

| Verifica | Esito |
|---|---|
| `:core:testDebugUnitTest` | 42 test, 0 falliti |
| `:code:testDebugUnitTest` | 48 test, 0 falliti |
| `:llm:testDebugUnitTest` | 59 test, 0 falliti |
| `:app:testDebugUnitTest` | 16 test, 0 falliti |
| **Totale** | **165 test, 0 falliti** |
| `:app:lintDebug` | 0 errori, 66 warning (dipendenze/AGP non aggiornate) |
| `:app:assembleDebug` | riuscita |
| Packaging e verifica APK | riuscita: 248.660.875 byte, firma valida, 3 ABI, modello integro |
| Compilazione nativa 3 ABI | riuscita |
| Test su dispositivo/emulatore | **non eseguiti** (nessun dispositivo disponibile) |
| Inferenza con modello di chat reale | **non eseguita** (nessun modello installato) |
| Download modelli, SAF, Keystore, SQLCipher, notifiche | **non verificati a runtime** |
| Valutazione qualità italiana con modello reale | **non eseguita** |

---

## 9. CI e pubblicazione

Il workflow `.github/workflows/android.yml` esegue: JDK 17, Android SDK 35, NDK e
CMake, fetch di llama.cpp, fetch del modello di embedding con verifica SHA-256,
build nativa delle tre ABI, test dei quattro moduli, lint, `assembleDebug`,
packaging con controllo ≥200 MiB, firma/manifest/ABI, SHA-256, upload
dell'artifact **BIG-B-AI-Debug-APK** (APK + `.sha256` + `.txt`, retention 30
giorni). Il workflow fallisce se il controllo di dimensione fallisce.

Esito della run [44904141926](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44904141926)
sul commit `f27d723`: **success**, tutti i passi verdi (fetch, build nativa 3 ABI, 165 test, lint,
assemble, verifica ≥200 MiB, upload).

| Dato artifact | Valore |
|---|---|
| Nome | `BIG-B-AI-Debug-APK` |
| ID | 14707228365 |
| ZIP | 167.696.447 byte, SHA-256 `9f943a36f040dc8d1c9441dcf0b095a60546612e1e4c9aa300b24fa1be1b9232` |
| APK | 248.075.965 byte (236,58 MiB) |
| APK SHA-256 | `23a47f3be138c6742da27392eb7ddd9f1b6d64a75419a10b6df00023ca05e064` |
| Scadenza | 21 dicembre 2026 (retention 30 giorni) |

Controlli sul file scaricato: `unzip -t` valido; `apksigner verify` valido (`CN=Android Debug`,
certificato SHA-256 `9733a06f…bccb`); `aapt2` conferma `com.bigbai.app.debug`, versionCode 6,
versionName 1.5.0-debug, targetSdk 35, ABI `arm64-v8a armeabi-v7a x86_64`; il modello dentro
l'APK ha 132.439.008 byte e SHA-256 `e011debc…77c93`; 24 file dex; `libbigbai.so` e
`libsqlcipher.so` presenti per tutte e tre le ABI. L'hash dell'APK coincide con il file `.sha256`
dell'artifact.

---

## 10. Problemi rimasti

1. **Nessuna verifica su dispositivo.** Avvio, UI, animazioni, font, inferenza,
   download, SAF, Keystore, SQLCipher, notifiche e sandbox non sono stati eseguiti.
2. **Nessuna valutazione reale della qualità italiana.** I controlli deterministici
   e il prompt sono implementati e testati, ma senza un modello di chat installato
   non esiste un punteggio di grammatica, comprensione o coerenza. Non viene
   dichiarato.
3. **Soglia di retrieval con margine stretto.** 0,80 separa le misure, ma non è una
   garanzia su documenti reali; un documento molto diverso dalla domanda può non
   essere recuperato.
4. **Costo del modello di embedding.** 132 MB di asset, ~150–200 MB di RAM quando
   caricato (stima, non misurata su telefono) e una copia iniziale di 132 MB nella
   memoria privata. Su dispositivi con poca RAM il modello viene scaricato sotto
   pressione e il retrieval torna lessicale.
5. **Nessuna GPU.** Solo CPU.
6. **Nessun compilatore Java/C++ sul dispositivo.** BIG B Code analizza ed esegue
   HTML/JS, non compila.
7. **Solo debug.** Firma `CN=Android Debug`, nessun AAB, nessuna release firmata.
8. **Copertura JVM non ricalcolata.** L'ultima misura (1.3.0) era 8,0%; i nuovi
   test aumentano la copertura della logica pura ma il dato non è stato rimisurato.
9. **66 warning di lint.** Sono dipendenze/AGP non aggiornate e suggerimenti di
   stile; nessun errore.
10. **Due `!!` in `ModelManagerScreen`.** Sono in un ramo già verificato non nullo
    (`status` deriva da `download`); non è stato possibile eseguire il percorso su
    dispositivo. Non sono stati rimossi per non alterare la build verificata.
