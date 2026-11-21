# Limiti noti

Questo documento elenca ciò che **non** è stato verificato o non è disponibile, senza edulcorare.
L'obiettivo è che nessuno scopra un limite solo dopo aver installato l'APK.

## Verifica

| Cosa | Stato |
|---|---|
| Compilazione debug | Verificata in questo ambiente |
| Test unitari JVM (124) | Verificati, 0 falliti |
| Agente BIG B Code (parsing, limiti, quota) | Verificato con test JVM |
| Quota giornaliera su dispositivo reale | **Non verificata**: logica e persistenza testate, ciclo su device no |
| Lint debug | 0 errori, 66 warning |
| Copertura JVM (JaCoCo) | Misurata: 8,0% delle linee; UI, Room, PDF e backup non coperti |
| Librerie native arm64-v8a, armeabi-v7a, x86_64 | Compilate e strippate, presenti nell'APK |
| Caricamento delle librerie su hardware reale | **Non verificato** |
| Avvio dell'app su dispositivo | **Non verificato** |
| Inferenza reale (testo) | **Non verificata** su device |
| Inferenza multimodale | **Non verificata** su device |
| Download di un modello completo | **Non eseguito**: il catalogo è verificato, il download no |
| SAF, Keystore, SQLCipher a runtime | **Non verificati** |
| Sandbox WebView | **Non verificata** a runtime |
| Notifiche e foreground service | **Non verificati** a runtime |
| Baseline Profiles | Valutati, **non inclusi** nella variante debug (AGP non li pacchettizza) |

Non era disponibile alcun dispositivo fisico né emulatore con accelerazione. Le parti sopra sono
implementate e compilano, ma non sono state eseguite: non vengono dichiarate "funzionanti".

## Piattaforma

- **Tre ABI, ma verifica solo in compilazione.** L'APK è universale (arm64-v8a, armeabi-v7a,
  x86_64) e ogni ABI ha il proprio `libbigbai.so`. Le librerie non sono state caricate su un
  dispositivo reale: un problema di runtime specifico di un'ABI non è stato escluso.
- **armv7 limitato dalla RAM.** Su 32-bit lo spazio di indirizzamento è ridotto: solo i modelli
  piccoli (Q2/Q3) entrano nel budget; gli altri vengono rifiutati con un messaggio.
- **Limite di 5 richieste locale al dispositivo.** Non esiste account né backend: il conteggio è
  salvato in DataStore e vale solo su quel dispositivo. Disinstallare l'app o cancellarne i dati
  lo azzera. Non è un limite globale per utente.
- **L'agente non compila.** Su Android non ci sono javac/clang: BIG B Code esegue analisi statica e
  la sandbox HTML, non build Java/C++. Il prompt vieta al modello di dichiarare build o test
  superati, e l'app mostra solo ciò che ha eseguito davvero.
- **Modelli piccoli e protocollo a strumenti.** Sotto ~1 B parametri il modello può sbagliare la
  sintassi degli strumenti: l'azione viene rifiutata con un errore nell'activity log, senza
  scrivere file parziali. Il ciclo è limitato a 5 passi per evitare loop.
- **Times New Roman non è incluso.** È un font proprietario: l'app usa Tinos (Apache-2.0),
  metricamente compatibile. La resa non è stata confrontata a schermo con Times New Roman.
- **Android 12+ (minSdk 31).** Richiesto per il comportamento delle foreground service e delle
  API di sicurezza usate.
- **GPU non disponibile.** Il backend Vulkan è escluso: la build di llama.cpp usata non include
  gli shader SPIR-V precompilati. Tutta l'inferenza è su CPU.
- **Nessun compilatore Java/C++ sul dispositivo.** Android non include `javac` né `clang`.
  BIG B Code offre analisi statica reale, non compilazione: l'interfaccia lo dichiara.
- **Nessun OCR.** I PDF scansionati non sono leggibili.
- **Nessun modello incluso.** L'APK contiene il motore; i modelli si scaricano (centinaia di MB).

## Consegna

- **Solo APK di debug.** La consegna è `BIG-B-AI-debug-1.4.0.apk`, firmato con `CN=Android Debug`:
  installabile, ma non adatto a Play Store né a una distribuzione "ufficiale". Non vengono
  prodotti AAB né release firmate.
- **R8/minify disattivato.** Scelta deliberata: non è stato possibile verificare su device che le
  regole di offuscamento non rompessero JGit, Room o il ponte JNI. Meglio un APK più grande che un
  crash non diagnosticabile.
- **Nessun test strumentale.** Non ci sono test Android (`androidTest`) eseguiti.
- **Copertura JVM bassa e parziale.** JaCoCo misura 8,0% delle linee complessive. La logica pura è
  coperta bene (`core/chat` 94,7%, `core/lang` 99,1%, `code` 33,7%, `llm` 23,5%), ma UI Compose,
  Room, PDF, backup, Keystore e sandbox non hanno test eseguibili sulla JVM. Il numero è misurato,
  non stimato, e non sostituisce i test su device.
- **Paging non introdotto.** La lista è lazy e il prompt è limitato, ma una conversazione con
  migliaia di messaggi carica tutte le righe della chat aperta.
- **Limiti allegati.** Immagini fino a 24 MB (poi ridimensionate a 896 px lato massimo), PDF fino a
  32 MB e 120.000 caratteri estratti (400 pagine). Oltre questi limiti l'app rifiuta o tronca e lo
  dichiara; nessun OCR.
- **Backup limitati a 64 MB.** Archivi più grandi vengono rifiutati prima dell'allocazione in RAM.

## Funzionalità

- **Lingua dell'interfaccia.** Le stringhe della UI sono in italiano; il selettore lingua imposta
  la lingua delle risposte del modello. La localizzazione completa IT/EN delle stringhe non è
  completata (esiste la configurazione locale `en`/`it` per il sistema).
- **Ricerca web.** Disattivata per default; richiede un endpoint SearXNG o una chiave Brave.
  La qualità dei risultati dipende dal provider. Offline viene segnalata come non disponibile.
- **Memoria.** Non è automatica: ogni salvataggio richiede conferma. Non esistono "ricordi
  impliciti" nascosti.
- **Tutor di lingua.** Non riaddestra i pesi del modello: usa un glossario curato e un lessico
  personale salvato nel database. Il lessico cresce solo quando il modello emette le righe
  `Lingua:XX` richieste; le traduzioni dipendono dal modello installato. Non è un corso completo e
  non sostituisce un insegnante.
- **Baseline Profiles.** Sono stati valutati: nella variante `debug` AGP non esegue
  `compileDebugArtProfile` nel grafo di `assembleDebug` e l'APK non contiene
  `assets/dexopt/baseline.prof` (verificato). Non vengono quindi inclusi né dichiarati guadagni.
- **Backup.** La password non è recuperabile: senza password il file `.bkb` è inutilizzabile.
  Il backup non include i modelli scaricati (solo dati e progetti).
- **Git.** È un repository locale privato dell'app (JGit), non un collegamento a GitHub o ad altri
  remote. Il ripristino riscrive i file nella cartella autorizzata.
- **Sandbox.** Esegue HTML/JS in una WebView isolata; Java e C++ non vengono eseguiti.
- **Profili RAM.** Il 70% è un tetto, non una garanzia: se il sistema è sotto pressione il
  caricamento può fallire, e l'errore viene mostrato.
- **Modelli molto grandi.** Su dispositivi con 6 GB di RAM, i modelli 7B in Q4 o superiori
  verranno rifiutati dal controllo del budget: è il comportamento previsto.

## Cosa serve per chiudere i limiti

1. Un dispositivo Android 12+ arm64 (o un emulatore arm64) per eseguire i test runtime.
2. Un keystore di release per firmare l'APK.
3. Shader SPIR-V verificati per abilitare Vulkan.
4. Una suite `androidTest` per SAF, Keystore e sandbox.
5. Un dispositivo per ogni ABI (arm64, armv7, x86_64) per verificare il caricamento di
   `libbigbai.so` e `libsqlcipher.so`.
