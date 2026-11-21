# ITAGPT 2.0.0 — report di release

Documento di consegna della fase 2.0.0. Gli stati descrivono **solo** ciò che è stato eseguito in
questa lavorazione; nessun test su dispositivo fisico o emulatore è stato possibile.

## Tabella finale

| Area | Stato | Verifica effettuata | Problemi residui |
|---|---|---|---|
| Rebranding | COMPLETATO E VERIFICATO | Nome, icona adattiva e monocromatica, splash, drawer, About, temi, stringhe, README, docs, script e workflow aggiornati; build Gradle verde | `applicationId` `com.bigbai.app` mantenuto per non perdere dati/aggiornamenti; URL del repository invariato |
| AI locale | IMPLEMENTATO, NON ANCORA VERIFICATO | Motore llama.cpp/mtmd compilato nei moduli; 214 test JVM verdi; nessuna esecuzione su hardware | Inferenza, caricamento ABI e download modello non eseguiti su device |
| Chat | IMPLEMENTATO, NON ANCORA VERIFICATO | Modifica/rigenerazione con troncamento nel DB, export Markdown/TXT/PDF, voce; test JVM su Markdown/TXT | UI, PDF e ciclo di generazione non provati su device |
| Code HTML | IMPLEMENTATO, NON ANCORA VERIFICATO | Sandbox WebView e analisi statica compilano; test JVM degli analizzatori | Sandbox non eseguita a runtime |
| Code Java | PARZIALE | Analisi statica reale (bilanciamento, classe pubblica, catch vuoti) e test JVM | Nessuna compilazione/esecuzione: Android non include `javac`; toolchain non integrato |
| Code C++ | PARZIALE | Analisi statica reale (bilanciamento, include guard) e test JVM | Nessuna compilazione/esecuzione: Android non include `clang`; NDK non spedito come compilatore |
| Vision/OCR | PARZIALE | Percorso multimodale `libmtmd` presente; PDF testuale con PDFBox | OCR assente; immagini solo con modello VL scaricato; nessun test su device |
| Voice | PARZIALE | TTS di sistema, stop della lettura, dettatura via recognizer di sistema | Nessuna conversazione vocale continua, selezione voce o cronologia; non provata su device |
| Interfaccia | IMPLEMENTATO, NON ANCORA VERIFICATO | Nuova identità, icona, drawer, scala testo 85–130%, temi chiaro/scuro/sistema | Nessuno screenshot o test visivo su device; localizzazione UI solo italiana |
| Impostazioni | PARZIALE | Tema, lingua risposte, dimensione testo, modelli, parametri AI, RAM, memoria, backup, ricerca, tutor, diagnostica funzionanti nel codice | Colori personalizzati e localizzazione completa IT/EN non implementati |
| Ottimizzazione | PARZIALE | Nessun nuovo limite artificiale di APK; build modulare invariata | Nessuna misura comparativa prima/dopo in questa release |
| Sicurezza | IMPLEMENTATO, NON ANCORA VERIFICATO | SQLCipher + Keystore, backup AES-GCM, nessuna telemetria, sandbox senza rete | Nessun test runtime di Keystore/SQLCipher/SAF; nessun audit esterno |
| Compatibilità | PARZIALE | APK universale arm64-v8a, armeabi-v7a, x86_64; compilazione SDK 35 | minSdk 31, non 26; nessun test su ABI/dispositivi reali |
| Test | PARZIALE | **214 test JVM, 0 falliti**; build debug e lint in CI | Nessun test strumentale o su device |
| APK | COMPLETATO E VERIFICATO | Artifact `ITAGPT-Debug-APK` scaricato; `sha256sum -c` OK; `aapt2` conferma package, versionCode 8, label ITAGPT e 3 ABI; `libbigbai.so`/`libsqlcipher.so` presenti per ogni ABI; modello di embedding presente; `apksigner` verifica schema v2 | Firma **debug** (`CN=Android Debug`); APK non installato né avviato su device |
| GitHub Actions | COMPLETATO E VERIFICATO | Run [44922648963](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44922648963), workflow `android.yml`: build nativa, 214 test, lint, APK, checksum e artifact completati con conclusione `success` | Nessuno; i test su device restano fuori dalla pipeline |

## Dati della build

| Campo | Valore |
|---|---|
| Versione | 2.0.0 (versionCode 8), variante debug |
| Package | `com.bigbai.app.debug` (debug), `com.bigbai.app` (release) |
| Minimo Android | 31 (Android 12) |
| Target SDK | 35 |
| Architetture | arm64-v8a, armeabi-v7a, x86_64 |
| Modelli integrati | embedding multilingual-e5-small Q8_0 (132.439.008 byte, SHA-256 verificato) |
| Modelli di chat | non inclusi; download dall'app con SHA-256 |
| Compilatori integrati | nessuno |
| Dimensione APK | **248.208.583 byte (236,71 MiB)** |
| Nome artifact | **ITAGPT-Debug-APK** (artifactId 14714403936) |
| SHA-256 | `fcafa35be77015db7dc14f2d6aeae578629489985028a3690c3cbbec490af292` |
| Firma | APK Signature Scheme v2, certificato debug SHA-256 `10b17b19383df435d9dbfdad86fbf5f1729e6d6eaa9237b05f0b3e50803301de` |
| Data build | 2026-11-21T16:15:38Z |
| Run CI | [44922648963](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44922648963) |

## Cosa è cambiato nel codice

1. **Branding** — sostituzione completa di nomi, stringhe e identificatori visibili; nuova icona
   vettoriale e variante monocromatica; tema rinominato; artifact e cartella Download rinominati.
2. **Chat** — `MessageDao.updateContent`/`deleteAfter`, `ChatRepository.editUserMessage` e
   `deleteMessagesAfter`, `ChatViewModel.regenerate`/`editMessage` e flusso `startSend(reuse)` che
   non duplica il messaggio utente; pulsanti di modifica/rigenerazione nelle bolle.
3. **Export** — `conversationMarkdown`, `conversationPlainText`, `conversationPdf` e
   `saveBytesToDownloads`; menu con tre formati.
4. **Voce** — `SpeechPlayer` (TTS di sistema) e dettatura tramite `ACTION_RECOGNIZE_SPEECH`; nessun
   permesso microfono richiesto all'app.
5. **Accessibilità** — `fontScale` persistito e applicato con `LocalDensity`.
6. **Packaging/CI** — rimosso il minimo artificiale di 200 MiB; checksum ricontrollato; report di
   test e lint caricati come artifact separati.

## Requisiti non realizzati in questa fase

| Requisito | Perché non è stato dichiarato completo | Alternativa concreta |
|---|---|---|
| Compilazione/esecuzione Java sul dispositivo | Android non fornisce `javac`; eseguire bytecode richiede un runtime aggiuntivo (es. ART/JVM embedded) non disponibile nella toolchain di questo progetto | Compilare in CI o su un server; integrare in futuro un compilatore Java embedded con sandbox e licenze verificate |
| Compilazione/esecuzione C++ sul dispositivo | Servirebbe l'intero toolchain Clang/NDK (centinaia di MB) con linker e sysroot, oltre a una sandbox di processo | Compilazione remota/CI; in locale resta l'analisi statica |
| OCR e PDF scansionati | Nessun motore OCR incluso; ML Kit/Tesseract richiedono dipendenza, modello e test su immagini reali | Integrare ML Kit text-recognition (modello bundled) o Tesseract, con test strumentali |
| DOCX/XLSX/CSV | Nessun parser incluso; l'export PDF/testo è implementato, l'import solo PDF/TXT | Aggiungere parser ZIP/XML per DOCX e un lettore XLSX, con limiti di dimensione |
| Conversazione vocale completa | Implementati solo TTS e dettatura; mancano selezione voce, ascolto continuo e cronologia | Estendere `SpeechPlayer` con `SpeechRecognizer` continuo e una schermata Voice dedicata |
| Android 8 / API 26 | Il progetto usa `MediaStore.Downloads` (API 29) e comportamenti di foreground service API 31; il declassamento richiede fallback di storage, permessi legacy e test su API 26–30 | Estrarre un `StorageWriter` con due implementazioni e una matrice di test su emulatori API 26/29/31 |
| Colori personalizzati e UI IT/EN completa | Palette fissa e stringhe italiane; il selettore lingua governa le risposte del modello | Estrarre i colori in tema dinamico e completare `values-en` |

## Test eseguiti

Comando locale:

```bash
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest \
  :llm:testDebugUnitTest :app:testDebugUnitTest assembleDebug
```

Risultato locale: **BUILD SUCCESSFUL**, **214 test, 0 falliti, 0 errori, 0 saltati**; lint
`:app:lintDebug`: **0 errori, 66 warning**.

Risultato CI: run **success**; artifact APK, checksum e report caricati. I test includono le nuove
verifiche su export Markdown/TXT. Non sono stati eseguiti test strumentali, test di UI, test su
device o misure di prestazioni comparative. L'APK è stato ispezionato (manifest, ABI, librerie,
modello, firma, checksum) ma **non installato né avviato**.
