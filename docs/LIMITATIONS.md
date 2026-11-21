# Limiti noti — ITAGPT 2.1

Questo documento elenca ciò che **non** è stato verificato o non è disponibile. L'obiettivo è che
nessuno scopra un limite solo dopo aver installato l'APK.

## Verifica

| Cosa | Stato |
|---|---|
| Compilazione debug/release (R8) | Verificata dalla CI; build release minificata |
| Test unitari JVM | **236 test, 0 falliti, 0 errori, 0 saltati** |
| Lint debug | **0 errori, 69 warning** |
| Librerie native arm64-v8a, armeabi-v7a, x86_64 | Compilate e verificate negli APK dalla CI |
| Modelli negli asset | **0 file**; gate di packaging |
| Import modello embedding | Implementato e testato come logica; **non eseguito su device** |
| Avvio app, Keystore, SQLCipher, SAF | **Non verificati** a runtime |
| Chat con modello reale | **Non verificata** su device |
| Chat senza modello | Implementata (messaggio + CTA); **non eseguita su device** |
| OCR immagini/PDF | Implementato con ML Kit; **non eseguito su device** |
| DOCX/XLSX/CSV/TXT | Parser coperti da test JVM; **non provati su file reali su device** |
| Voce (TTS, dettatura) | Implementata; **non provata su device** |
| Ricerca web end-to-end | Provider verificati da host (Google News, Wikipedia); **non provata nell'app su device** |
| Tablet, mouse, trackpad, tastiera fisica | Layout e scorciatoie implementati; **non provati su hardware** |
| Firma release ufficiale | **BLOCCATA**: keystore non configurato nei GitHub Secrets |

Non era disponibile alcun dispositivo fisico né emulatore. Le parti sopra compilano e hanno test
JVM dove possibile, ma non sono state eseguite su Android: non vengono dichiarate “funzionanti”.

## Modelli e chat

- **Nessun modello è incluso.** La prima risposta richiede che l'utente scelga e scarichi un modello
  dalla libreria. È una scelta esplicita di 2.1, non un errore: “nessun modello nell'APK” e “nessun
  download automatico” rendono impossibile una risposta generata al primo avvio senza intervento.
- **Senza modello la chat non risponde.** Mostra un errore chiaro e un pulsante verso Modelli. Non
  c'è una risposta simulata, non c'è un caricamento infinito.
- **Qualità linguistica non misurata in 2.1.** Il modello 1.5B non è più nel pacchetto e non è stato
  eseguito un benchmark equivalente. I prompt sono stati rafforzati, ma nessun miglioramento è
  dichiarato come misurato.
- **L'embedding non è incluso.** È opzionale e va importato; senza di esso la ricerca semantica è
  disattivata. Non viene scaricato automaticamente.

## Ricerca web

- **Google News RSS** e **Wikipedia API** sono stati interrogati con successo da questo ambiente.
- **DuckDuckGo HTML** ha risposto `202` (pagina anti-bot) dall'IP datacenter: 0 risultati. Su rete
  mobile può funzionare, ma qui non è verificato.
- **SearXNG pubblico** ha risposto con HTML/challenge o `429`, non con JSON.
- **Brave Search** richiede una chiave e non è stato provato senza credenziali.
- Non vengono inventate fonti, date o link. Se tutti i provider falliscono, l'app lo dice.
- La lettura delle pagine è best-effort: paywall, JavaScript obbligatorio o blocchi anti-bot possono
  lasciare solo lo snippet del provider.

## Prestazioni

- **Nessun benchmark su device.** Non ci sono numeri di TTFT, tok/s, RAM, throttling o consumo per
  Xiaomi Pad 8 o realme 16 Pro. La differenza osservata dall'utente è la motivazione del profilo
  adattivo, non una misura prodotta qui.
- **CPU only.** `n_gpu_layers = 0`: la build non include un backend GPU verificato. Nessuna
  accelerazione è dichiarata.
- `PerformanceAdvisor` riduce thread e contesto in base a stato termico e battery saver; la policy è
  testata, l'effetto reale no.
- Su armv7 lo spazio di indirizzamento è ridotto: un modello grande può essere rifiutato dal budget.

## Interfaccia e periferiche

- Il layout adattivo (drawer permanente, contenuto max 1200 dp) compila ma **non è stato visto** su
  un tablet o foldable.
- Mouse, trackpad, hover, focus e scorrimento con rotellina non sono stati provati.
- Le scorciatoie `Ctrl+N`, `Ctrl+K`, `Ctrl+L`, `Esc` sono implementate; non sono state provate con
  una tastiera fisica.
- La revisione visiva di tutte le schermate secondarie, del contrasto e dei font non è stata
  completata.
- La localizzazione non è completa: molte schermate storiche usano testo italiano inline invece di
  risorse `values/strings.xml`.

## Voce, OCR e documenti

- **Voce parziale:** dettatura tramite app di sistema e TTS; nessuna conversazione full-duplex,
  nessuna registrazione/riproduzione integrata. Non dichiarata completa.
- OCR e documenti sono implementati e compilano; nessun file reale è stato elaborato su device.
- PDF scansionati: rendering fino a 20 pagine, poi OCR. Oltre quel limite il testo non è completo.
- `.doc` e `.xls` legacy sono rifiutati esplicitamente.

## ITAGPT Code

- Nessun terminale, compilatore Java/C++ o build reale sul dispositivo: Android non include
  `javac`/`clang`. Non viene simulato nulla.
- Il limite è 12 passi per attività; oltre, l'utente può scrivere «continua».
- Le modifiche richiedono conferma per l'eliminazione e restano nella cartella autorizzata.

## Sicurezza e release

- Database cifrato con chiave in Android Keystore; nessun audit runtime è stato eseguito.
- Nessun segreto è nel repository.
- La release è firmata con la chiave debug finché i quattro Secrets non sono configurati; non è una
  release ufficiale.
- Gli APK non sono stati installati né avviati in questo ambiente.
