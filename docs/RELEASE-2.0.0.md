# ITAGPT 2.0.0 — report di release

Documento di consegna della fase 2.0.0. Gli stati descrivono **solo** ciò che è stato eseguito in
questa lavorazione; nessun test su dispositivo fisico o emulatore è stato possibile.

## Tabella finale

| Area | Stato | Verifica effettuata | Problemi residui |
|---|---|---|---|
| Build debug | COMPLETATO E VERIFICATO | `assembleDebug` con JDK 17 e SDK 35; APK incluso nell'artifact CI | Non installato né avviato su device |
| Build release + R8 | COMPLETATO E VERIFICATO | `assembleRelease` con R8, minificazione e resource shrinking; regole keep per JNI/Room/reflection/JGit/PDFBox/SQLCipher/ML Kit | Runtime offuscato non provato su device |
| Modello chat incluso | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | Qwen2.5 1.5B Q4_K_M negli asset, 1.117.320.736 byte, SHA-256 verificato da script e packaging; chat forzata sul modello incluso | Estrazione, caricamento e inferenza mai eseguiti su Android |
| Qualità italiana | PARZIALE | Valutazione reale su host con gli stessi parametri dell'app: 8/8 casi EOS per il 1.5B; 0.5B scartato per ripetizioni | Errori di contenuto residui (grammatica, spelling, fatti); non è una certificazione |
| OCR immagini | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | ML Kit Latin incluso, EXIF, bitmap campionata, anteprima copia/inserisci, errore esplicito se non c'è testo | Nessuna foto o screenshot reale elaborato su device |
| OCR PDF scansionati | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | `PdfRenderer` + OCR fino a 20 pagine, distinto dall'estrazione testo PDFBox | Nessun PDF scansionato reale elaborato su device |
| Import DOCX/XLSX/CSV/TXT | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | Parser ZIP/XML locali, limiti 32 MB/120.000 caratteri, test JVM; `.doc`/`.xls` rifiutati | Picker e resa su file reali non provati su device |
| Voce | PARZIALE | TTS di sistema, stop, lingua `auto/it/en`, auto-speak, dettatura di sistema; impostazioni persistenti | Nessuna conversazione continua o full-duplex; nessun test device |
| Code Java | BLOCCATO | Analisi statica reale e test JVM | Nessuna compilazione/esecuzione: Android non include `javac`; toolchain embedded non integrato |
| Code C++ | BLOCCATO | Analisi statica reale e test JVM | Nessuna compilazione/esecuzione: Android non include `clang`; NDK non spedito come compilatore |
| Interfaccia e restyle | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | Tema ITAGPT, icona, drawer, scala testo 85–130%, schermate voce/OCR/documenti; compila | Nessuno screenshot o test visivo su device |
| Test | COMPLETATO E VERIFICATO | **222 test JVM, 0 falliti, 0 errori, 0 saltati**; lint **0 errori, 69 warning** | Nessun test strumentale o su device |
| APK | COMPLETATO E VERIFICATO | Debug e release costruiti in CI; ABI, modelli, firma, manifest e checksum verificati dagli script; artifact pubblicati | Firma debug, non release ufficiale; APK non installato su device |
| Firma di release ufficiale | BLOCCATO | La pipeline supporta i quattro Secrets e fallisce se la firma resta debug | `ITAGPT_KEYSTORE_BASE64`, `ITAGPT_KEYSTORE_PASSWORD`, `ITAGPT_KEY_ALIAS`, `ITAGPT_KEY_PASSWORD` non configurati |
| Test su dispositivo/emulatore | BLOCCATO | — | Nessun dispositivo o emulatore disponibile in questo ambiente |

## Dati della build

| Campo | Valore |
|---|---|
| Versione | 2.0.0 (versionCode 9) |
| Commit del codice | `601e4e2` (`freebuff/ead2cc9450775fd211011b13`) |
| Package | `com.bigbai.app` (release), `com.bigbai.app.debug` (debug) |
| Minimo Android | 31 (Android 12) |
| Target SDK | 35 |
| Architetture | arm64-v8a, armeabi-v7a, x86_64 (APK universale) |
| Modello chat | Qwen2.5 1.5B Instruct Q4_K_M, 1.117.320.736 byte, SHA-256 `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| Modello embedding | multilingual-e5-small Q8_0, 132.439.008 byte, SHA-256 `e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93` |
| Compilatori inclusi | nessuno |
| Run CI | [44932000433](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/44932000433) |
| Artifact debug | **ITAGPT-Debug-APK** |
| Artifact release | **ITAGPT-Release-APK** |
| Firma | chiave debug `C=US, O=Android, CN=Android Debug` (release non ufficiale) |

<!-- APK_DATA -->

## Cosa è cambiato nel codice

1. **Modello incluso** — `BundledModels.CHAT` e `BundledChatModel`: copia dagli asset, verifica
   SHA-256, controllo spazio libero, marker di installazione e lock contro doppie copie. Il
   modello è usato obbligatoriamente al primo avvio; nessun download o API key.
2. **Prompt e sampler** — prompt compatto per i modelli piccoli, sezione OCR/documenti, parametri
   più stabili per il 1.5B (temperatura 0,55, top-k 30, top-p 0,90, min-p 0,03, repetition penalty
   1,15, massimo 640 token).
3. **OCR** — `OcrEngine` con ML Kit Latin, rotazione EXIF, bitmap campionata, OCR di PDF
   scansionati fino a 20 pagine, anteprima con copia/inserimento e messaggi di errore.
4. **Documenti** — `DocumentTextExtractor` per DOCX/XLSX/CSV/TXT, nuovo `Attachment.Kind.DOCUMENT`,
   picker nella chat, limiti di dimensione e rifiuto esplicito dei formati binari legacy.
5. **Voce** — `voiceLanguage` e `autoSpeak` persistiti, sintesi automatica delle nuove risposte,
   Stop che interrompe anche il TTS, lingua applicata a TTS e recognizer.
6. **Release** — R8 e resource shrinking attivi solo in release, regole keep per JNI, Room,
   serializzazione, JGit, PDFBox, SQLCipher e ML Kit; firma condizionale e fallback debug
   etichettato.
7. **CI** — download e verifica dei due modelli, build nativa per tre ABI, 222 test, lint, APK
   debug e release, checksum, `apksigner`, `aapt2` e tre artifact separati.

## Valutazione del modello

I report completi sono in `docs/ai-eval/`:

| Modello | Esito | Dettagli |
|---|---|---|
| Qwen2.5 1.5B Q4_K_M (incluso) | 8/8 EOS | Italiano leggibile; grammatica/spelling ancora imperfetti; spiegazione del cielo blu con invenzioni |
| Qwen2.5 0.5B Q4_K_M (scartato) | 7/8 EOS, 1 LENGTH | Ripetizioni di frasi, ortografia e spiegazioni inaffidabili |

La suite misura solo segnali oggettivi (EOS/LENGTH, ripetizioni, spazi doppi, forme errate note).
Non è un benchmark linguistico e non dimostra che il modello sia adatto a uso professionale. Il
modello 1.5B è stato scelto perché il 0.5B falliva visibilmente sugli stessi prompt.

## Requisiti non realizzati

| Requisito | Stato | Perché | Alternativa concreta |
|---|---|---|---|
| Compilazione/esecuzione Java sul dispositivo | BLOCCATO | Android non fornisce `javac`; eseguire bytecode richiede un runtime aggiuntivo e una sandbox non disponibili nella toolchain | Compilare in CI o integrare un compilatore Java embedded con sandbox e licenze verificate |
| Compilazione/esecuzione C++ sul dispositivo | BLOCCATO | Servirebbe l'intero toolchain Clang/NDK con linker, sysroot e sandbox di processo | Compilazione remota/CI; in locale resta l'analisi statica |
| Firma di release ufficiale | BLOCCATO | Nessun keystore nei Secrets e nessuna chiave può essere inventata o committata | Configurare i quattro `ITAGPT_*` Secrets e rieseguire la pipeline |
| Test su dispositivo | BLOCCATO | Nessun dispositivo fisico o emulatore con accelerazione in questo ambiente | Eseguire i test runtime su Android 12+ arm64 e su emulatore x86_64 |
| Conversazione vocale continua | PARZIALE | Implementati TTS, dettatura, lingua e auto-speak; manca l'ascolto continuo/full-duplex | Estendere il recognizer con sessione continua e gestione dell'interruzione |
| OCR su device | IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO | Il codice e la dipendenza compilano; nessuna immagine reale è stata elaborata su Android | Test strumentali con foto, screenshot e PDF scansionati |
| Qualità italiana di livello elevato | PARZIALE | Un modello 1.5B non può garantire accuratezza fattuale; la suite mostra errori residui | Usare un modello più grande scaricabile o verificare le risposte |

## Test eseguiti

```bash
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest \
  :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon assembleRelease
```

Risultati locali: **BUILD SUCCESSFUL**, **222 test, 0 falliti, 0 errori, 0 saltati**; lint
`:app:lintDebug`: **0 errori, 69 warning**; release R8 completata. La CI ripete test, lint, build
nativa e packaging su un runner pulito.

Non sono stati eseguiti test strumentali, test di UI, test su device o misure di prestazioni
comparative. Gli APK sono stati ispezionati (manifest, ABI, librerie, modelli, firma, checksum) ma
**non installati né avviati**.
