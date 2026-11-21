# Modelli

## Modello di chat incluso nell'APK

ITAGPT 2.0.0 include un modello conversazionale reale, non un segnaposto:

| Voce | Valore |
|---|---|
| Modello | **Qwen2.5 1.5B Instruct**, quantizzazione **Q4_K_M** |
| File | `qwen2.5-1.5b-instruct-q4_k_m.gguf` |
| Dimensione | **1.117.320.736 byte** (1,07 GiB) |
| SHA-256 | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| Licenza | **Apache-2.0** (`Qwen/Qwen2.5-1.5B-Instruct-GGUF`) |
| Formato | GGUF, Q4_K_M, template ChatML |
| Posizione | `app/src/main/assets/models/` e, al primo avvio, archivio privato dell'app |
| RAM stimata | ~1,45 GiB a 4.096 token di contesto; almeno 4 GB di RAM consigliati |

L'app copia il modello dagli asset al primo avvio, ne verifica dimensione e SHA-256 e lo carica con
llama.cpp. Non serve un download, un account o una API key: la chat è disponibile subito dopo
l'installazione, anche senza rete. L'estrazione iniziale richiede spazio e tempo e viene mostrata
nell'onboarding.

Il modello è stato scelto dopo una valutazione reale su host x86_64 con lo stesso template e gli
stessi parametri dell'app. I report con prompt e risposte sono in:

- `docs/ai-eval/qwen2.5-1.5b-instruct-q4_k_m-itagpt-2.0.0.md` — **modello incluso**: 8/8 casi
  chiusi con EOS, italiano leggibile, errori di contenuto residui;
- `docs/ai-eval/qwen2.5-0.5b-instruct-q4_k_m-itagpt-2.0.0.md` — 0.5B, **scartato**: ripetizioni e
  italiano inaffidabile.

La suite non è una certificazione: un modello da 1,5B resta limitato e può inventare fatti. Non
usarlo come fonte medica, legale o fattuale senza verifica.

## Modello di embedding incluso

Non è nel catalogo dei download: è dentro l'APK e non viene scaricato.

| Voce | Valore |
|---|---|
| Modello | `multilingual-e5-small` Q8_0 GGUF (`TwinSunsLLC/multilingual-e5-small-gguf`) |
| Dimensione | 132.439.008 byte |
| SHA-256 | `e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93` |
| Licenza | **MIT** (`cardData.license` dell'API Hugging Face; base `intfloat/multilingual-e5-small`, MIT) |
| Ruolo | retrieval semantico locale: trasforma domande e passaggi in vettori, non genera testo |
| Soglia | 0,83, misurata in `docs/RETRIEVAL-1.5.1.md` |
| RAM | 132 MB di modello + 48 MiB di runtime + 56 MiB di margine (vedi `EmbeddingMemoryPolicy`) |

Il modello è verificato con SHA-256 e dimensione all'installazione e accettato agli avvii
successivi solo se il marker `InstallMarker` v2 coincide per hash, dimensione e mtime.

## Catalogo dei modelli scaricabili

Il catalogo è in `llm/src/main/assets/model_catalog.json`. Contiene 7 famiglie Qwen2.5, tutte
Apache-2.0, con hash SHA-256 dei file LFS verificati contro l'API di Hugging Face. I modelli del
catalogo non sono inclusi nell'APK: si scaricano dall'app, con ripresa e verifica SHA-256.

| Famiglia | Ruolo | Parametri | Quantizzazioni (dimensione file) |
|---|---|---|---|
| Qwen2.5 0.5B Instruct | chat | 0.5B | Q2_K (396 MB), Q3_K_M (412 MB), Q4_K_M (469 MB), Q5_K_M (498 MB), Q6_K (620 MB) |
| Qwen2.5 1.5B Instruct | chat | 1.5B | Q2_K (718 MB), Q3_K_M (882 MB), Q4_K_M (1066 MB), Q5_K_M (1226 MB), Q6_K (1396 MB) |
| Qwen2.5 3B Instruct | chat | 3B | Q2_K (1313 MB), Q3_K_M (1644 MB), Q4_K_M (2007 MB), Q5_K_M (2326 MB), Q6_K (2664 MB) |
| Qwen2.5 7B Instruct | chat | 7B | Q2_K (2876 MB), Q3_K_M (3632 MB), Q4_K_M (4466 MB), Q5_K_M (5193 MB), Q6_K (5964 MB) |
| Qwen2.5 Coder 1.5B Instruct | code | 1.5B | Q2_K (718 MB), Q4_K_M (1066 MB), Q5_K_M (1226 MB), Q6_K (1396 MB) |
| Qwen2.5 Coder 7B Instruct | code | 7B | Q2_K (2876 MB), Q3_K_M (3632 MB), Q4_K_M (4466 MB) |
| Qwen2.5 VL 3B Instruct | vision | 3B | Q4_K_M (1840 MB), Q8_0 (3133 MB) |

Il modello incluso corrisponde alla variante `qwen2.5-1.5b-instruct-q4_k_m` del catalogo: chi
sceglie un'altra variante la scarica e la installa, senza duplicare quella inclusa.

## Quantizzazioni

| Quant | Uso tipico | Trade-off |
|---|---|---|
| Q2_K | RAM molto limitata | Massima compressione, perdita di qualità sensibile |
| Q3_K_M | Dispositivi con 6 GB | Buon compromesso su modelli piccoli |
| Q4_K_M | Scelta predefinita e modello incluso | Miglior rapporto qualità/dimensione nella pratica |
| Q5_K_M | 8 GB+ | Qualità alta, più lento |
| Q6_K | 12 GB+ | Quasi lossless, richiede molta RAM |
| Q8_0 | Solo VL | Usato per il proiettore visivo, non per il modello base |

## Profili

| Profilo | Ruolo | Modello suggerito |
|---|---|---|
| Veloce | Risposte brevi, bassa latenza | Qwen2.5 1.5B Q3–Q4 (incluso) / 0.5B solo se la RAM è il vincolo |
| Equilibrato | Uso quotidiano | Qwen2.5 1.5B / 3B Q4 |
| Intelligente | Ragionamento e codice | Qwen2.5 3B Q4–Q5, Coder 1.5B |
| Massimo | Qualità, dispositivi con molta RAM | Qwen2.5 7B Q2–Q4, Coder 7B |

## Budget RAM

`DeviceProfile.budgetBytes()` calcola il tetto:

- profilo **Normale**: 50% della RAM fisica;
- profilo **Avanzato**: 70% della RAM fisica.

Il valore è un **tetto di progetto**, non una prenotazione: prima del caricamento il motore
verifica la memoria realmente disponibile e rifiuta il modello se non entra, invece di lasciare
che il sistema lo uccida. La RAM stimata per un modello combina peso dei file, KV cache
(`layers × kvHeads × headDim × context × 2 × 2 byte`) e overhead del runtime.

Il context length predefinito è adattivo: parte dal valore di training e scende a 16.384 / 8.192 /
4.096 in base allo spazio residuo. Può essere forzato nelle impostazioni avanzate.

## Download

1. Scelta della variante nel catalogo.
2. Download con header `Range`: se esiste un file parziale, riprende da lì.
3. Scrittura su `.part`, avanzamento persistito nel database.
4. Al completamento: SHA-256 calcolato sul file e confrontato con il catalogo.
5. Solo se coincide, il file viene spostato atomicamente nella posizione definitiva.
6. Notifica di esito; in caso di errore il file parziale resta riprendibile.

Lo spazio libero viene controllato prima di iniziare. I download avvengono in un foreground
service `dataSync`, quindi continuano con l'app in background.

## Visione e OCR

Sono due percorsi distinti:

- **OCR di testo**: ML Kit Latin Text Recognition, incluso nella libreria e eseguito sul
  dispositivo. Legge immagini, screenshot, foto e PDF scansionati (fino a 20 pagine) e restituisce
  testo italiano/inglese. Non richiede un modello multimodale e non invia immagini in rete.
- **Descrizione visiva**: richiede un modello multimodale **Qwen2.5-VL 3B** (Q4_K_M o Q8_0) e il
  file `mmproj` della stessa famiglia, scaricati e verificati. Senza proiettore l'interfaccia non
  offre la descrizione: non viene simulata.

Il testo OCR può essere copiato o inserito nel messaggio; il modello di chat lo usa come contesto.
La qualità su foto reali non è stata misurata su device.

## PDF e documenti

`PdfExtractor` usa PDFBox-Android (`com.tom-roush:pdfbox-android:2.0.27.0`) per estrarre il testo
pagina per pagina. Se un PDF non contiene testo, l'app propone l'OCR e lo dichiara. Per DOCX,
XLSX, CSV e TXT `DocumentTextExtractor` usa parser ZIP/XML locali; `.doc` e `.xls` binari sono
rifiutati con un messaggio esplicito.

## Modificare il catalogo

Aggiungere una voce in `model_catalog.json` con:

```json
{
  "slug": "...",
  "family": "...",
  "role": "CHAT | CODE | VISION",
  "tier": "FAST | BALANCED | SMART | MAX",
  "params": "3B",
  "license": "Apache-2.0",
  "licenseUrl": "...",
  "sourceUrl": "...",
  "repo": "owner/repo",
  "contextTrain": 32768,
  "layers": 36,
  "kvHeads": 2,
  "headDim": 128,
  "variants": [
    { "quant": "Q4_K_M", "files": [ { "name": "...gguf", "size": 123, "sha256": "..." } ] }
  ]
}
```

Gli hash devono essere quelli reali del file: il download viene rifiutato se non coincidono.
