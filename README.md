# ITAGPT 2.3

Assistente AI locale per Android (Kotlin, Jetpack Compose, llama.cpp/JNI), con chat, OCR offline,
import documenti, ricerca web automatica, memoria locale e ambiente di sviluppo ITAGPT Code.

`applicationId com.bigbai.app` e namespace restano invariati per conservare installazioni e dati.
La versione è **2.3.0** (`versionCode 12`).

> **Cambio di architettura in 2.1:** nessun modello AI è incluso nell'APK. Qwen2.5 1.5B e il
> modello di embedding sono stati rimossi dal pacchetto. L'app si installa e si avvia senza modelli,
> senza download automatici e senza API key. La chat richiede un modello scelto e scaricato
> esplicitamente dalla sezione Modelli; l'embedding è opzionale e si importa da un file locale.
>
> **2.3 completa ITAGPT Code con strumenti reali:** terminale sul progetto (comandi documentati,
> cronologia, timeout, interruzione), verifica distinta in analisi statica / validazione dati /
> esecuzione / compilazione, parser reali per JSON, XML e CSV, esecuzione HTML e JavaScript nella
> sandbox, agente estratto in un motore testabile, rollback con rilevamento delle modifiche
> dell'utente e ripristino verificato byte per byte. Java, Kotlin, C/C++ e Python restano **solo
> analisi statica**: Android non include quei compilatori e l'app lo dichiara.

## Stato dichiarato

Stati usati: `COMPLETATO E VERIFICATO`, `IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO`, `PARZIALE`,
`NON IMPLEMENTATO`, `BLOCCATO`.

| Area | Stato | Prova / limite |
|---|---|---|
| Nessun modello nell'APK | **COMPLETATO E VERIFICATO** | 0 file `.gguf` nel pacchetto; gate CI che fallisce se ricompaiono |
| Qwen rimosso dal pacchetto | **COMPLETATO E VERIFICATO** | asset e script di fetch eliminati; APK 2.1 molto più piccolo |
| Supporto embedding mantenuto, non incluso | **IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO** | import SAF + verifica SHA-256; test JVM; nessun caricamento su device |
| Avvio e navigazione senza modelli | **IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO** | nessun crash previsto, stato vuoto con CTA; non eseguito su Android |
| Chat senza modello (messaggio chiaro, nessuna risposta finta) | **IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO** | errore esplicito e nessuna generazione; non provato su device |
| Ricerca Internet automatica | **IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO** | provider reali e fallback; Google News RSS e Wikipedia verificati da host, DuckDuckGo bloccato dall'IP datacenter |
| Fonti con titolo, sito, data, link | **IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO** | modello `Source` esteso, parsing XML/HTML testato; UI non provata su device |
| UI/UX e accessibilità | **PARZIALE** | pulsante web, stato ricerca, fonti, stato senza modello, layout adattivo; nessun test visivo su device |
| Tablet, mouse, trackpad, tastiera fisica | **PARZIALE** | drawer permanente sotto 1200 dp, contenuto limitato in larghezza, Ctrl+N/Ctrl+K/Ctrl+L/Esc; non provati su hardware |
| Prestazioni e rilevamento hardware | **PARZIALE** | profilo termico/batteria con test JVM; nessun benchmark su Xiaomi Pad 8 / realme 16 Pro |
| AI Code | **PARZIALE** | terminale reale sul progetto, verifica con esecuzione HTML/JS e validazione dati, rollback protetto e verificato, motore dell'agente testato su JVM; compilazione Java/Kotlin/C/C++ non disponibile e dichiarata; nessun test su device |
| Voce | **PARZIALE** | dettatura di sistema e TTS; niente full-duplex |
| OCR e documenti | **IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO** | ML Kit Latin, PDF scansionati, DOCX/XLSX/CSV/TXT; parser testati, nessun file reale su device |
| Localizzazione IT/EN | **PARZIALE** | molte schermate storiche hanno testo italiano inline; non è stata fatta una revisione completa |
| Privacy e sicurezza | **PARZIALE** | database cifrato, nessun segreto nel repository, permessi invariati; audit runtime non eseguito |
| Firma release ufficiale | **BLOCCATO** | i quattro GitHub Secrets del keystore non sono configurati |
| Test su dispositivo fisico/emulatore | **BLOCCATO** | nessun dispositivo disponibile in questo ambiente |

Il report completo, con comandi, dimensioni e limiti, è in
[docs/RELEASE-2.3.0.md](docs/RELEASE-2.3.0.md).

## APK 2.3

La run CI di riferimento è la **#45025333599**, build del commit `120f020` (workflow **Android CI -
ITAGPT debug and release**, conclusa con successo: native, 364 test JVM, lint, debug e release).
Gli APK sono negli artifact GitHub Actions, non nel repository.

| APK | Dimensione | SHA-256 | Firma |
|---|---|---|---|
| `ITAGPT-debug-2.3.0.apk` | 150.010.223 byte (143,06 MiB) | `82bd914fc7843352f542c9c9aa935ac5198a085de6cfb2770b9c74bd7252c3a6` | debug |
| `ITAGPT-release-debug-signed-2.3.0.apk` | 80.389.559 byte (76,67 MiB) | `df9a043bc250123c7dc367a531ce56615e3d949b80d5cbf5b02efe4d87d90435` | chiave debug, **non ufficiale** |

Ogni artifact contiene l'APK, il file `.sha256` e un report `.txt` con dimensione, data, ABI,
firma e conferma dell'assenza di modelli. Link diretti:

- [artifact debug](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/45025333599/artifacts/14755674175) (ZIP 59.604.663 byte);
- [artifact release](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/45025333599/artifacts/14755812502) (ZIP 40.368.035 byte);
- [report CI](https://github.com/bertoccimatteo70-commits/big-b-ai/actions/runs/45025333599/artifacts/14756400092) (ZIP 66.006 byte).

Il download degli artifact richiede l'accesso a GitHub.

L'APK è universale: `arm64-v8a`, `armeabi-v7a`, `x86_64`. Non ci sono split per ABI e non esiste un
limite artificiale di dimensione.

## Cosa è cambiato in 2.3

### ITAGPT Code 2.3

- **Terminale reale** sulla cartella del progetto: `ls`, `cat`, `grep`, `find`, `tree`, `mkdir`,
  `touch`, `cp`, `mv`, `rm`, `echo` con redirezione, `stat`, `sha256`, `run`, `verify`, `git`,
  `history`, `help`. Cronologia, interruzione, timeout e output limitato.
- **Verifica distinta**: analisi statica, validazione JSON/XML/CSV con parser reali, esecuzione
  HTML/JavaScript nella WebView isolata, compilazione dichiarata non disponibile. Nessun
  controllo viene presentato come eseguito se non lo è.
- **Agente testabile**: il ciclo dell'agente vive in `AgentRunner` (Kotlin puro) e viene usato
  dall'app; i test JVM coprono scritture multiple, errori del provider, timeout, budget, conferma
  dell'eliminazione e checkpoint fallito.
- **Rollback più sicuro**: le modifiche dell'utente successive all'attività vengono rilevate e
  lasciate intatte; il ripristino viene verificato byte per byte; il rollback automatico è
  opzionale e richiede tutte le condizioni di sicurezza.
- **Nessuna dipendenza nuova**: il terminale, i validatori e il verificatore sono codice del
  progetto, senza librerie aggiuntive.
- La compilazione Java/Kotlin/C/C++ **non è disponibile** su Android: non viene simulata.

## Cosa è cambiato in 2.1

### Modelli

- Rimossi `qwen2.5-1.5b-instruct-q4_k_m.gguf` e `multilingual-e5-small-q8_0.gguf` dagli asset.
- Eliminati `BundledChatModel`, la copia automatica dagli asset e gli script
  `fetch-bundled-chat.sh` / `fetch-bundled-model.sh`.
- Nessun download parte da solo. La sezione Modelli mostra il catalogo e l'utente sceglie.
- L'embedding è opzionale: **Impostazioni → Ricerca semantica → Importa .gguf**. Il file viene
  copiato nella memoria privata e accettato solo se dimensione e SHA-256 corrispondono a
  `multilingual-e5-small-q8_0.gguf`. Senza file, la ricerca semantica è disattivata e il resto
  dell'app funziona.
- Il gate di packaging rifiuta qualunque APK che contenga `assets/**/*.gguf`.

### Ricerca Internet

- Default: **automatica**, senza provider, endpoint o chiave da scegliere.
- Catena reale: Brave (solo se hai salvato una chiave) → SearXNG (solo se hai salvato un endpoint)
  → Google News RSS per notizie e finestre temporali → DuckDuckGo → Wikipedia.
- Deduplicazione per URL canonico, filtro temporale (`day`, `week`, `month`, `year`), lettura della
  pagina per le prime tre fonti, metadati `article:published_time` / `og:site_name` quando presenti.
- Pulsante globo nella chat con stato attivo/disattivo e indicatore di ricerca in corso.
- Le fonti sono mostrate con titolo, sito, data (se dichiarata) e link apribile; il prompt vieta di
  citare fonti assenti e di presentare conoscenze interne come verifiche online.
- Google News RSS e Wikipedia sono stati interrogati realmente da questo ambiente. DuckDuckGo ha
  risposto `202` (anti-bot) dall'IP datacenter: su rete mobile può funzionare, ma non è dichiarato
  verificato qui.

### Prestazioni

- `PerformanceAdvisor` combina core, stato termico Android e risparmio energetico per ridurre thread
  e contesto prima del throttling. È coperto da test JVM.
- Il contesto adattivo e il budget RAM restano quelli esistenti; nessuna GPU è dichiarata.
- Non sono state misurate latenze su dispositivo: nessun numero di tok/s viene inventato.

### UI, tablet e tastiera

- Window size class: su tablet/foldable il drawer diventa un pannello permanente e il contenuto è
  limitato a 1200 dp, invece di allargare il layout del telefono.
- Scorciatoie: `Ctrl+N` nuova chat, `Ctrl+K` ricerca nella cronologia, `Ctrl+L` attiva/disattiva la
  ricerca web, `Esc` interrompe la generazione.
- Stato “nessun modello” con pulsante reale verso la libreria; nessun caricamento infinito.
- Fonti compatte con sito e data; pannello fonti completo.

## Verifiche eseguite

- Build Kotlin/Compose: `:app:compileDebugKotlin` riuscita.
- Test JVM: **364 test, 0 falliti, 0 errori, 0 saltati** su `core` (60), `code` (171), `llm` (108), `app` (25).
- Lint debug: **0 errori, 70 warning**.
- Parser ricerca: test su DuckDuckGo HTML, Google News RSS, metadati pagina, deduplica URL.
- Provider reali da host: Google News RSS (HTTP 200, 102 item) e Wikipedia API (HTTP 200).
- APK debug/release, FIRMA, ABI, assenza modelli e checksum: verificati dalla CI e riportati nel
  report 2.3.

Non sono stati eseguiti test strumentali, di rendering o su dispositivo: nessun dispositivo fisico
o emulatore era disponibile. Le affermazioni “funziona su tablet / mouse / tastiera” non sono
supportate da una prova e non vengono fatte.

## Build

Requisiti: JDK 17, Android SDK 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.

```bash
bash scripts/fetch-llama.sh
ABIS="arm64-v8a armeabi-v7a x86_64" bash scripts/build-native.sh
./gradlew :core:testDebugUnitTest :code:testDebugUnitTest :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew assembleDebug assembleRelease
bash scripts/package-debug-apk.sh
bash scripts/package-release-apk.sh
```

Nessuno script scarica modelli. Per la ricerca semantica in locale, importa il file dalla UI.

## Limiti principali

- **Senza modello non c'è risposta generata.** È la conseguenza diretta di “nessun modello
  nell'APK” e “nessun download automatico”; l'app lo dice e porta alla libreria.
- **Qualità linguistica non misurabile qui.** Il modello non è più incluso; non è stato eseguito
  alcun benchmark 2.1 su un LLM reale. I prompt sono stati rafforzati, ma non viene dichiarato un
  miglioramento misurato.
- **Nessun test su device.** OCR, voce, Keystore, SAF, rendering, tablet e periferiche restano non
  verificati a runtime.
- **Firma ufficiale bloccata** finché non vengono configurati i Secrets.
- **CPU only.** `n_gpu_layers = 0`: la build non include un backend GPU verificato.

Dettagli completi in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## Licenze

Codice ITAGPT: vedere il repository. Componenti principali: llama.cpp (MIT), Kotlin/AndroidX
(Apache-2.0), OkHttp (Apache-2.0), JGit (EPL-2.0), Tinos (Apache-2.0), ML Kit Text Recognition
(Google), SQLCipher (BSD-style, Zetetic). I modelli scaricabili mantengono la licenza del publisher;
`multilingual-e5-small` Q8_0 è MIT. Nessun modello è ridistribuito in questo repository o nell'APK.
