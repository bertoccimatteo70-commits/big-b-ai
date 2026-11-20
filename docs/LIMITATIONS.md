# Limiti noti

Questo documento elenca ciò che **non** è stato verificato o non è disponibile, senza edulcorare.
L'obiettivo è che nessuno scopra un limite solo dopo aver installato l'APK.

## Verifica

| Cosa | Stato |
|---|---|
| Compilazione debug e release | Verificata in questo ambiente |
| Test unitari JVM (18) | Verificati |
| Libreria nativa arm64 | Compilata e strippata |
| Avvio dell'app su dispositivo | **Non verificato** |
| Inferenza reale (testo) | **Non verificata** su device |
| Inferenza multimodale | **Non verificata** su device |
| Download di un modello completo | **Non eseguito**: il catalogo è verificato, il download no |
| SAF, Keystore, SQLCipher a runtime | **Non verificati** |
| Sandbox WebView | **Non verificata** a runtime |
| Notifiche e foreground service | **Non verificati** a runtime |

Non era disponibile alcun dispositivo fisico né emulatore con accelerazione. Le parti sopra sono
implementate e compilano, ma non sono state eseguite: non vengono dichiarate "funzionanti".

## Piattaforma

- **Solo arm64-v8a.** Nessuna build x86_64 o armeabi-v7a: l'APK non si installa su emulatori x86
  senza traduzione.
- **Android 12+ (minSdk 31).** Richiesto per il comportamento delle foreground service e delle
  API di sicurezza usate.
- **GPU non disponibile.** Il backend Vulkan è escluso: la build di llama.cpp usata non include
  gli shader SPIR-V precompilati. Tutta l'inferenza è su CPU.
- **Nessun compilatore Java/C++ sul dispositivo.** Android non include `javac` né `clang`.
  BIG B Code offre analisi statica reale, non compilazione: l'interfaccia lo dichiara.
- **Nessun OCR.** I PDF scansionati non sono leggibili.
- **Nessun modello incluso.** L'APK contiene il motore; i modelli si scaricano (centinaia di MB).

## Release

- **Firma debug.** L'APK è firmato con `CN=Android Debug` perché non è disponibile un keystore di
  release. Installabile, ma non adatto a Play Store né a una distribuzione "ufficiale".
- **R8/minify disattivato.** Scelta deliberata per la v1: non è stato possibile verificare su
  device che le regole di offuscamento non rompessero JGit, Room o il ponte JNI. Meglio un APK
  più grande che un crash non diagnosticabile.
- **Nessun test strumentale.** Non ci sono test Android (`androidTest`) eseguiti.

## Funzionalità

- **Lingua dell'interfaccia.** Le stringhe della UI sono in italiano; il selettore lingua imposta
  la lingua delle risposte del modello. La localizzazione completa IT/EN delle stringhe non è
  completata (esiste la configurazione locale `en`/`it` per il sistema).
- **Ricerca web.** Disattivata per default; richiede un endpoint SearXNG o una chiave Brave.
  La qualità dei risultati dipende dal provider. Offline viene segnalata come non disponibile.
- **Memoria.** Non è automatica: ogni salvataggio richiede conferma. Non esistono "ricordi
  impliciti" nascosti.
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
