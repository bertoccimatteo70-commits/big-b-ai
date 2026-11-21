# ITAGPT 2.1

Assistente AI locale per Android (Kotlin, Jetpack Compose, llama.cpp/JNI), con chat, OCR offline,
import documenti, ricerca web automatica, memoria locale e ambiente di sviluppo ITAGPT Code.

`applicationId com.bigbai.app` e namespace restano invariati per conservare installazioni e dati.
La versione è **2.1.0** (`versionCode 10`).

> **Cambio di architettura in 2.1:** nessun modello AI è incluso nell'APK. Qwen2.5 1.5B e il
> modello di embedding sono stati rimossi dal pacchetto. L'app si installa e si avvia senza modelli,
> senza download automatici e senza API key. La chat richiede un modello scelto e scaricato
> esplicitamente dalla sezione Modelli; l'embedding è opzionale e si importa da un file locale.

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
| AI Code | **PARZIALE** | limite portato da 5 a 12 passi, checkpoint e diff esistenti; nessun terminale o compilatore reale |
| Voce | **PARZIALE** | dettatura di sistema e TTS; niente full-duplex |
| OCR e documenti | **IMPLEMENTATO NON VERIFICATO SU DISPOSITIVO** | ML Kit Latin, PDF scansionati, DOCX/XLSX/CSV/TXT; parser testati, nessun file reale su device |
| Localizzazione IT/EN | **PARZIALE** | molte schermate storiche hanno testo italiano inline; non è stata fatta una revisione completa |
| Privacy e sicurezza | **PARZIALE** | database cifrato, nessun segreto nel repository, permessi invariati; audit runtime non eseguito |
| Firma release ufficiale | **BLOCCATO** | i quattro GitHub Secrets del keystore non sono configurati |
| Test su dispositivo fisico/emulatore | **BLOCCATO** | nessun dispositivo disponibile in questo ambiente |

Il report completo, con comandi, dimensioni e limiti, è in
[docs/RELEASE-2.1.0.md](docs/RELEASE-2.1.0.md).

## APK 2.1

La run CI di riferimento è la build del commit `f11f1b9` (workflow **Android CI - ITAGPT debug and
release**). Gli APK sono negli artifact GitHub Actions, non nel repository.

| APK | Dimensione | SHA-256 | Firma |
|---|---|---|---|
| `ITAGPT-debug-2.1.0.apk` | `{{DEBUG_SIZE}}` | `{{DEBUG_SHA}}` | debug |
| `ITAGPT-release-debug-signed-2.1.0.apk` | `{{RELEASE_SIZE}}` | `{{RELEASE_SHA}}` | chiave debug, **non ufficiale** |

Ogni artifact contiene l'APK, il file `.sha256` e un report `.txt` con dimensione, data, ABI,
firma e conferma dell'assenza di modelli. I link agli artifact sono pubblicati nel report
[docs/RELEASE-2.1.0.md](docs/RELEASE-2.1.0.md) appena la run è conclusa.

L'APK è universale: `arm64-v8a`, `armeabi-v7a`, `x86_64`. Non ci sono split per ABI e non esiste un
limite artificiale di dimensione.

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

### ITAGPT Code

- Limite portato da 5 a 12 passi per attività, con tetti invariati su file e azioni.
- Restano lettura/scrittura/eliminazione con conferma, checkpoint, diff, analisi statica e
  continuazione con «continua».
- Non esiste un terminale o un compilatore Android reale: non viene simulato.

## Verifiche eseguite

- Build Kotlin/Compose: `:app:compileDebugKotlin` riuscita.
- Test JVM: **236 test, 0 falliti, 0 errori, 0 saltati** su `core`, `llm`, `code`, `app`.
- Lint debug: **0 errori, 69 warning**.
- Parser ricerca: test su DuckDuckGo HTML, Google News RSS, metadati pagina, deduplica URL.
- Provider reali da host: Google News RSS (HTTP 200, 102 item) e Wikipedia API (HTTP 200).
- APK debug/release, FIRMA, ABI, assenza modelli e checksum: verificati dalla CI e riportati nel
  report 2.1.

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
