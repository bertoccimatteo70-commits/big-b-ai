# Retrieval semantico — misura della soglia (1.5.1)

La soglia di similarità non è più un valore scelto a intuito. È stata misurata sul modello
effettivamente incluso nell'APK, con lo stesso motore (llama.cpp) e lo stesso pooling usati
dall'app.

## Setup

| Voce | Valore |
|---|---|
| Modello | `multilingual-e5-small-q8_0.gguf`, 132.439.008 byte, SHA-256 `e011debc…877c93` |
| Motore | llama.cpp host x86_64, CPU, `--pooling mean --embd-normalize 2` |
| Prefissi | `query: ` per le domande, `passage: ` per i documenti (come in `Embeddings.kt`) |
| Similarità | coseno, top-3 documenti, come in `SemanticIndex.search` |
| Script | `python3 scripts/eval-retrieval.py` (dataset incluso nello script) |

## Dataset

10 documenti italiani su argomenti distinti (conservazione dei pomodori, carbonara, SQLite,
Monte Bianco, fotosintesi, Costituzione, batteria Android, scala musicale, raffreddore,
attività fisica) e 18 domande:

- 8 domande pertinenti con parafrasi o sinonimi (es. «Serve un server per usare SQLite?»);
- 2 domande ambigue ma pertinenti (raffreddore, attività fisica);
- 8 domande non pertinenti, di cui una vicina al dominio dei pomodori («Come si conserva il
  basilico fresco?») per testare i falsi positivi difficili.

## Risultati

| Soglia | Precision | Recall | F1 | Falsi positivi | Falsi negativi | Domande estranee con un passaggio |
|---:|---:|---:|---:|---:|---:|---:|
| 0,76 | 0,294 | 1,000 | 0,455 | 24 | 0 | 6/8 |
| 0,78 | 0,455 | 1,000 | 0,625 | 12 | 0 | 4/8 |
| 0,79 | 0,526 | 1,000 | 0,690 | 9 | 0 | 4/8 |
| **0,80** | **0,769** | **1,000** | **0,870** | **3** | **0** | **1/8** |
| 0,81 | 0,909 | 1,000 | 0,952 | 1 | 0 | 1/8 |
| **0,82** | **1,000** | **1,000** | **1,000** | **0** | **0** | **0/8** |
| **0,83** | **1,000** | **1,000** | **1,000** | **0** | **0** | **0/8** |
| 0,84 | 1,000 | 0,900 | 0,947 | 0 | 1 | 0/8 |
| 0,86 | 1,000 | 0,900 | 0,947 | 0 | 1 | 0/8 |
| 0,88 | 1,000 | 0,700 | 0,824 | 0 | 3 | 0/8 |
| 0,90 | 1,000 | 0,200 | 0,333 | 0 | 8 | 0/8 |

La finestra con precision e recall entrambi a 1,000 è **0,82–0,83**. Il valore scelto è
**0,83**, al centro della finestra: 0,01 di margine dai falsi positivi e 0,01 dai falsi
negativi. `SemanticRepository.MIN_SCORE` è stato portato da 0,80 a 0,83.

## Interpretazione

- A 0,80 il sistema avrebbe inserito 3 passaggi non pertinenti nel contesto e avrebbe
  recuperato un passaggio anche per una domanda completamente estranea («capitale della
  Francia»). Nel prompt questi passaggi spingono il modello a rispondere su argomenti non
  richiesti.
- A 0,83 nessuna domanda estranea recupera nulla e tutte le 10 domande pertinenti trovano il
  documento giusto.
- La misura è su 10 documenti e 18 domande: è un campione piccolo e sintetico, non una
  garanzia universale. Con documenti reali lunghi e multi-argomento la distribuzione cambia.
  Lo script è ripetibile: va rilanciato su un corpus reale prima di considerare la soglia
  definitiva per un dominio specifico.
- Il modello di embedding non genera risposte: decide solo quali passaggi entrano nel
  contesto. La qualità della risposta resta responsabilità del modello conversazionale.

## Riproduzione

```bash
# motore host già compilato in build/llama-host
python3 scripts/eval-retrieval.py --threshold 0.83
```

Lo script stampa la tabella completa e il valore `BEST`. Se si cambia modello di embedding
o pooling, la soglia va rimisurata: non è trasferibile.
