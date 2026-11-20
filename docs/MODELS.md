# Modelli

## Catalogo

Il catalogo è in `llm/src/main/assets/model_catalog.json`. Contiene 7 famiglie Qwen2.5, tutte
Apache-2.0, con hash SHA-256 dei file LFS verificati contro l'API di Hugging Face.

| Famiglia | Ruolo | Parametri | Quantizzazioni (dimensione file) |
|---|---|---|---|
| Qwen2.5 0.5B Instruct | chat | 0.5B | Q2_K (396 MB), Q3_K_M (412 MB), Q4_K_M (469 MB), Q5_K_M (498 MB), Q6_K (620 MB) |
| Qwen2.5 1.5B Instruct | chat | 1.5B | Q2_K (718 MB), Q3_K_M (882 MB), Q4_K_M (1066 MB), Q5_K_M (1226 MB), Q6_K (1396 MB) |
| Qwen2.5 3B Instruct | chat | 3B | Q2_K (1313 MB), Q3_K_M (1644 MB), Q4_K_M (2007 MB), Q5_K_M (2326 MB), Q6_K (2664 MB) |
| Qwen2.5 7B Instruct | chat | 7B | Q2_K (2876 MB), Q3_K_M (3632 MB), Q4_K_M (4466 MB), Q5_K_M (5193 MB), Q6_K (5964 MB) |
| Qwen2.5 Coder 1.5B Instruct | code | 1.5B | Q2_K (718 MB), Q4_K_M (1066 MB), Q5_K_M (1226 MB), Q6_K (1396 MB) |
| Qwen2.5 Coder 7B Instruct | code | 7B | Q2_K (2876 MB), Q3_K_M (3632 MB), Q4_K_M (4466 MB) |
| Qwen2.5 VL 3B Instruct | vision | 3B | Q4_K_M (1840 MB), Q8_0 (3133 MB) |

## Quantizzazioni

| Quant | Uso tipico | Trade-off |
|---|---|---|
| Q2_K | RAM molto limitata | Massima compressione, perdita di qualità sensibile |
| Q3_K_M | Dispositivi con 6 GB | Buon compromesso su modelli piccoli |
| Q4_K_M | Scelta predefinita | Miglior rapporto qualità/dimensione nella pratica |
| Q5_K_M | 8 GB+ | Qualità alta, più lento |
| Q6_K | 12 GB+ | Quasi lossless, richiede molta RAM |
| Q8_0 | Solo VL | Usato per il proiettore visivo, non per il modello base |

## Profili

| Profilo | Ruolo | Modello suggerito |
|---|---|---|
| Veloce | Risposte brevi, bassa latenza | Qwen2.5 0.5B / 1.5B Q2–Q4 |
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

Il context length predefinito è adattivo: parte dal valore di training (32.768) e scende a
16.384 / 8.192 / 4.096 in base allo spazio residuo. Può essere forzato nelle impostazioni avanzate.

## Download

1. Scelta della variante nel catalogo.
2. Download con header `Range`: se esiste un file parziale, riprende da lì.
3. Scrittura su `.part`, avanzamento persistito nel database.
4. Al completamento: SHA-256 calcolato sul file e confrontato con il catalogo.
5. Solo se coincide, il file viene spostato atomicamente nella posizione definitiva.
6. Notifica di esito; in caso di errore il file parziale resta riprendibile.

Lo spazio libero viene controllato prima di iniziare. I download avvengono in un foreground
service `dataSync`, quindi continuano con l'app in background.

## Visione

Il supporto immagini è reale solo con un modello multimodale:

- modello: **Qwen2.5-VL 3B Instruct** (Q4_K_M o Q8_0);
- proiettore: file `mmproj` della stessa famiglia, scaricato e verificato insieme al modello;
- percorso nativo: `libmtmd` (mtmd) con immagine RGB ridimensionata.

Se il modello attivo non ha un proiettore, l'interfaccia non offre l'analisi immagini: non viene
simulata. Il testo estratto dai PDF, invece, funziona con qualsiasi modello di testo.

## PDF

`PdfExtractor` usa PDFBox-Android (`com.tom-roush:pdfbox-android:2.0.27.0`) per estrarre il testo
pagina per pagina. I PDF scansionati (solo immagini) non producono testo: non c'è OCR, e l'app lo
dice invece di inventare contenuto.

## Modificare il catalogo

Aggiungere una voce in `model_catalog.json` con slug, famiglia, ruolo, tier, parametri, licenza,
repo di origine, `contextTrain`, `layers`, `kvHeads`, `headDim` e la lista di varianti con nome file,
dimensione e SHA-256. Gli hash devono essere quelli reali del file: il download viene rifiutato se
non coincidono.
