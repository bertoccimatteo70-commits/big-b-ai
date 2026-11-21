# Limiti noti

Questo documento elenca ciò che **non** è stato verificato o non è disponibile, senza edulcorare.
L'obiettivo è che nessuno scopra un limite solo dopo aver installato l'APK.

## Verifica

| Cosa | Stato |
|---|---|
| Compilazione debug e release (R8) | Verificate in questo ambiente; build release minificata completata |
| Test unitari JVM (222) | Verificati, 0 falliti, 0 errori, 0 saltati |
| Lint debug | Eseguito; warning riportati nella CI |
| Librerie native arm64-v8a, armeabi-v7a, x86_64 | Compilate e strippate dalla CI, presenti negli APK |
| Modello chat incluso (Qwen2.5 1.5B Q4_K_M) | Verificato per dimensione e SHA-256 nello script e nell'APK; **non caricato su device** |
| Modello di embedding incluso | Verificato per dimensione e SHA-256; **non caricato su device** |
| OCR su immagini reali | Implementato con ML Kit sul dispositivo; **non eseguito su device** |
| OCR su PDF scansionati | Implementato con `PdfRenderer` + ML Kit; **non eseguito su device** |
| Import DOCX/XLSX/CSV/TXT | Parser locali coperti da test JVM; **non provati su file reali su device** |
| Voce (TTS, dettatura, lingua, auto-speak) | Implementata; **non provata su device** |
| Avvio dell'app, Keystore, SQLCipher, SAF | **Non verificati** a runtime |
| Inferenza reale su Android | **Non verificata** su device |
| Firma di release ufficiale | **BLOCCATA**: nessun keystore nei GitHub Secrets |

Non era disponibile alcun dispositivo fisico né emulatore con accelerazione. Le parti sopra sono
implementate e compilano, ma non sono state eseguite su Android: non vengono dichiarate
"funzionanti".

## Modello incluso e qualità

- **Il modello incluso è Qwen2.5 1.5B Instruct Q4_K_M**, 1.117.320.736 byte, SHA-256
  `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`, licenza Apache-2.0.
  È il modello più piccolo che ha superato in modo accettabile la suite italiana reale
  (`docs/ai-eval/qwen2.5-1.5b-instruct-q4_k_m-itagpt-2.0.0.md`). Il 0.5B, provato sullo stesso
  protocollo, ripeteva frasi e produceva italiano inaffidabile.
- **Non è un modello di frontiera.** La suite mostra risposte grammaticalmente accettabili e
  tutti gli 8 casi chiusi con EOS, ma restano errori di contenuto: per esempio la spiegazione di
  «qual è»/«qual'è» è sbagliata e la spiegazione del cielo blu contiene invenzioni. Non va usato
  come fonte medica, legale o fattuale senza verifica.
- **RAM necessaria.** Il modello pesa 1,12 GB; con KV cache a 4.096 token e overhead la stima è
  circa 1,45 GiB. In pratica servono almeno **4 GB di RAM** e circa **3 GB liberi** per installare
  l'APK e copiare il modello negli asset privati. Su 2–3 GB il caricamento può essere rifiutato
  dal budget RAM: l'app mostra l'errore, non finge di aver caricato il modello.
- **Primo avvio.** L'estrazione del modello dagli asset richiede tempo e spazio; l'onboarding
  mostra l'avanzamento. Non è un download di rete, ma non è istantanea.
- **La qualità dipende anche dal prompt e dal sampler.** Il profilo dei modelli piccoli usa
  temperatura 0,55, top-k 30, top-p 0,90, min-p 0,03, repetition penalty 1,15 e massimo 640
  token. Questi valori sono una scelta misurata, non una garanzia.
- **Modelli più grandi** restano scaricabili dal catalogo. Non sono inclusi nell'APK e richiedono
  spazio, RAM e tempo di download aggiuntivi.

## Piattaforma e prestazioni

- **Tre ABI, ma verifica solo in compilazione.** L'APK è universale (arm64-v8a, armeabi-v7a,
  x86_64) e ogni ABI ha il proprio `libbigbai.so`. Le librerie non sono state caricate su un
  dispositivo reale: un problema di runtime specifico di un'ABI non è stato escluso.
- **armv7 limitato dalla RAM.** Su 32-bit lo spazio di indirizzamento è ridotto: il modello 1.5B
  Q4 può non entrare nel budget; l'app lo rifiuta con un messaggio invece di lasciare che il
  sistema la uccida.
- **CPU only.** Il backend Vulkan è escluso: la build di llama.cpp usata non include gli shader
  SPIR-V precompilati. La velocità su telefono non è stata misurata.
- **Android 12+ (minSdk 31).** Non è stato declassato ad API 26: l'app usa `MediaStore.Downloads`
  e comportamenti di foreground service introdotti con API 31. Servono fallback di storage,
  permessi legacy e test su API 26–30 prima di dichiarare Android 8 supportato.
- **Limite di 5 richieste locale al dispositivo.** Non esiste account né backend: il conteggio è
  salvato in DataStore e vale solo su quel dispositivo. Disinstallare l'app o cancellarne i dati
  lo azzera.

## OCR, documenti e voce

- **OCR solo latino.** ML Kit Latin Text Recognition copre italiano e inglese, non alfabeti
  cirillici, greci, arabi o CJK. Il modello è incluso nella libreria e l'elaborazione avviene sul
  dispositivo: l'immagine non viene inviata in rete.
- **PDF scansionati fino a 20 pagine.** Ogni pagina viene renderizzata e passata all'OCR; oltre
  20 pagine l'app avvisa e si ferma. I PDF con testo usano l'estrazione PDFBox, non l'OCR.
- **Qualità OCR non misurata su foto reali.** Rotazione EXIF, riflessi, ombre e scansioni storte
  possono produrre testo errato. L'app mostra un errore esplicito se non trova testo, invece di
  inventarlo.
- **DOCX/XLSX/CSV sono parser locali, non Office.** DOCX legge paragrafi e tabelle; XLSX legge i
  fogli e le celle con shared strings; CSV/TXT sono testo. Non esegue macro, formule, grafici o
  formattazione. I vecchi `.doc`/`.xls` binari sono rifiutati con un messaggio che chiede di
  salvarli come `.docx`/`.xlsx`.
- **Allegati limitati.** Immagini fino a 24 MB, PDF e documenti fino a 32 MB; i PDF testuali
  estraggono al massimo 120.000 caratteri. Oltre questi limiti l'app rifiuta o tronca e lo dice.
- **Voce non continua.** Sono disponibili TTS di sistema, lettura automatica opzionale,
  interruzione con Stop e dettatura tramite il recognizer del dispositivo. Non esistono ascolto
  continuo, conversazione full-duplex, selezione della voce o cronologia vocale. L'audio non viene
  registrato né conservato dall'app e non viene richiesto il permesso microfono.
- **Lingua voce.** Il selettore `auto`/`it`/`en` governa TTS e riconoscimento quando il dispositivo
  supporta la lingua; se manca il pacchetto lingua, il sistema può rifiutare la sintesi. L'errore
  viene mostrato.

## Codice e sandbox

- **Nessun compilatore Java/C++ sul dispositivo.** Android non include `javac` né `clang`.
  ITAGPT Code offre analisi statica reale, editor, patch, Git locale e sandbox HTML; non esegue
  build Java/C++ e l'interfaccia non lo dichiara disponibile. Integrare un toolchain completo
  (compilatore, linker, sysroot, sandbox di processo) non è stato possibile in questa release.
- **La sandbox esegue HTML/JS in una WebView isolata**, con rete bloccata per default. Non è un
  container di sicurezza certificato e non esegue codice nativo.
- **L'agente non compila.** Il prompt vieta al modello di dichiarare build o test superati; l'app
  mostra solo ciò che ha eseguito davvero. Il ciclo è limitato a 5 passi per evitare loop.
- **Modelli piccoli e protocollo a strumenti.** Il modello 1.5B può sbagliare la sintassi degli
  strumenti: l'azione viene rifiutata con un errore nell'activity log, senza scrivere file
  parziali.

## Dati, sicurezza e consegna

- **SQLCipher e Keystore non sono stati provati a runtime.** La chiave è protetta da Android
  Keystore e il database è cifrato, ma un test su device è ancora necessario.
- **Backup limitati a 64 MB.** Archivi più grandi vengono rifiutati prima dell'allocazione in RAM.
  La password non è recuperabile: senza password il file `.bkb` è inutilizzabile.
- **Nessuna telemetria.** Le uniche connessioni di rete sono i download di modelli scelti e la
  ricerca web se abilitata; OCR e chat non chiamano servizi esterni.
- **R8/minify attivo in release.** La build minificata è stata prodotta e le regole keep coprono
  JNI, Room, serializzazione, JGit, PDFBox, SQLCipher e ML Kit. **Non è stata eseguita su device**:
  un problema di reflection non è stato escluso.
- **Firma di release ufficiale assente.** Senza i quattro GitHub Secrets la release è firmata con
  la chiave debug ed è chiamata `ITAGPT-release-debug-signed-2.0.0.apk`; non è una release
  ufficiale. Con i Secrets la pipeline richiede la firma corretta e fallisce se resta quella debug.
- **Nessun test strumentale.** Non ci sono test Android (`androidTest`) eseguiti. La copertura JVM
  è parziale: UI Compose, Room, PDF, backup, Keystore, voce, OCR e sandbox non hanno test
  eseguibili sulla JVM.
- **Nessuna misura di velocità su telefono.** Token/s, tempo al primo token e consumo energetico
  non sono stati misurati su hardware Android.

## Cosa serve per chiudere i limiti

1. Un dispositivo Android 12+ arm64 (o un emulatore arm64) per eseguire i test runtime.
2. Un keystore di release nei GitHub Secrets per una firma ufficiale.
3. Test strumentali per SAF, Keystore, SQLCipher, OCR, voce e sandbox.
4. Un dispositivo per ogni ABI (arm64, armv7, x86_64) per verificare il caricamento di
   `libbigbai.so` e `libsqlcipher.so`.
5. Un toolchain Java/C++ embedded con sandbox, se si vuole compilare davvero sul telefono.
