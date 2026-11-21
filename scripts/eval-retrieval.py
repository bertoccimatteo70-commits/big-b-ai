#!/usr/bin/env python3
"""Measures the retrieval threshold on the bundled embedding model.

Runs the real multilingual-e5-small Q8_0 GGUF through llama.cpp's
`llama-embedding` (the same model and pooling used by the app), computes cosine
similarity on an Italian dataset, and reports precision / recall / F1 for every
threshold between 0.60 and 0.90.

The dataset has relevant, paraphrased, ambiguous and unrelated queries, so the
chosen threshold is based on measurements rather than on a guess. The app's
`SemanticRepository.MIN_SCORE` must match the threshold printed as BEST.

Usage:
    python3 scripts/eval-retrieval.py [--threshold 0.80]
"""
import argparse
import json
import math
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(ROOT, "models", "multilingual-e5-small-q8_0.gguf")
BINARY = os.path.join(ROOT, "build", "llama-host", "bin", "llama-embedding")
LIBDIR = os.path.join(ROOT, "build", "llama-host", "bin")

# Ten Italian passages, each one the only correct source for its query.
DOCUMENTS = {
    "D1": "I pomodori si conservano meglio a temperatura ambiente, lontano dalla luce diretta. "
          "In frigorifero perdono aroma e diventano farinosi.",
    "D2": "Per una carbonara autentica servono guanciale, pecorino romano, uova e pepe nero. "
          "La panna non fa parte della ricetta tradizionale.",
    "D3": "SQLite è un database embedded: non richiede un server, salva i dati in un singolo file "
          "e supporta transazioni ACID.",
    "D4": "Il Monte Bianco è la vetta più alta delle Alpi, con 4.808 metri. Si trova al confine "
          "tra Italia e Francia.",
    "D5": "La fotosintesi clorofilliana converte anidride carbonica e acqua in glucosio usando "
          "l'energia della luce solare.",
    "D6": "In Italia la Costituzione è entrata in vigore il 1º gennaio 1948. La Repubblica è "
          "fondata sul lavoro.",
    "D7": "Per ridurre il consumo di batteria su Android, limita i servizi in background e abbassa "
          "la frequenza di aggiornamento dello schermo.",
    "D8": "La scala maggiore è formata da sette note secondo lo schema "
          "tono-tono-semitono-tono-tono-tono-semitono.",
    "D9": "Il raffreddore comune è causato da virus e dura in genere da sette a dieci giorni; "
          "gli antibiotici non servono.",
    "D10": "Per un allenamento aerobico efficace bastano 150 minuti a settimana di attività "
           "moderata, come camminata veloce o corsa leggera.",
}

# expected = None means the query is deliberately outside the corpus.
QUERIES = {
    "Q1": ("Come devo conservare i pomodori per non farli diventare insipidi?", "D1"),
    "Q2": ("Quali ingredienti servono per preparare la pasta alla carbonara?", "D2"),
    "Q3": ("Serve un server per usare SQLite?", "D3"),
    "Q4": ("Quanto è alto il Monte Bianco?", "D4"),
    "Q5": ("Come le piante producono zucchero dalla luce?", "D5"),
    "Q6": ("Quando è entrata in vigore la Costituzione italiana?", "D6"),
    "Q7": ("Come posso consumare meno batteria sul telefono Android?", "D7"),
    "Q8": ("Quante note ci sono nella scala maggiore e con quali intervalli?", "D8"),
    "Q9": ("Come si cura il raffreddore?", "D9"),
    "Q10": ("Quanta attività fisica serve ogni settimana?", "D10"),
    "Q11": ("Come si conserva il basilico fresco?", None),
    "Q12": ("Qual è la capitale della Francia?", None),
    "Q13": ("Come si cambia la gomma di una bicicletta?", None),
    "Q14": ("Chi ha dipinto la Gioconda?", None),
    "Q15": ("Qual è la differenza tra TCP e UDP?", None),
    "Q16": ("Come si prepara un caffè espresso?", None),
    "Q17": ("Quanto costa un biglietto per il cinema?", None),
    "Q18": ("Come si allena un cane a sedersi?", None),
}

TOP_K = 3
_cache = {}


def embed(text):
    if text in _cache:
        return _cache[text]
    env = dict(os.environ)
    env["LD_LIBRARY_PATH"] = LIBDIR + (":" + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    completed = subprocess.run(
        [BINARY, "-m", MODEL, "-p", text, "--pooling", "mean",
         "--embd-normalize", "2", "--embd-output-format", "json", "-t", "4"],
        capture_output=True, text=True, env=env, check=True,
    )
    payload = json.loads(completed.stdout)
    vector = payload["data"][0]["embedding"]
    norm = math.sqrt(sum(value * value for value in vector)) or 1.0
    _cache[text] = [value / norm for value in vector]
    return _cache[text]


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b))


def metrics(threshold):
    tp = fp = fn = 0
    unrelated_hits = 0
    for query, (text, expected) in QUERIES.items():
        query_vector = embed("query: " + text)
        ranked = sorted(
            ((doc, cosine(query_vector, embed("passage: " + passage)))
             for doc, passage in DOCUMENTS.items()),
            key=lambda item: item[1],
            reverse=True,
        )
        retrieved = [doc for doc, score in ranked[:TOP_K] if score >= threshold]
        if expected is None:
            if retrieved:
                unrelated_hits += 1
                fp += len(retrieved)
            continue
        if expected in retrieved:
            tp += 1
            fp += len(retrieved) - 1
        else:
            fn += 1
            fp += len(retrieved)
    precision = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / (tp + fn) if tp + fn else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1, fp, fn, unrelated_hits


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--threshold", type=float, default=None,
                        help="also print the metrics at this threshold")
    args = parser.parse_args()

    if not os.path.isfile(MODEL):
        sys.exit("Modello assente: esegui prima scripts/fetch-bundled-model.sh")
    if not os.access(BINARY, os.X_OK):
        sys.exit("llama-embedding assente: compila il motore host in build/llama-host")

    print(f"Modello: {MODEL}")
    print(f"Documenti: {len(DOCUMENTS)} · Query: {len(QUERIES)} "
          f"(10 pertinenti/parafrasi/ambigue, 8 non pertinenti)\n")
    print(f"{'Soglia':>7} {'Precision':>10} {'Recall':>8} {'F1':>8} {'FP':>4} {'FN':>4} {'Hit su 8':>9}")
    best = None
    for step in range(60, 91):
        threshold = step / 100.0
        precision, recall, f1, fp, fn, unrelated = metrics(threshold)
        print(f"{threshold:>7.2f} {precision:>10.3f} {recall:>8.3f} {f1:>8.3f} "
              f"{fp:>4} {fn:>4} {unrelated:>9}")
        # Prefer the highest F1; on a tie prefer higher precision, then the
        # higher threshold (fewer irrelevant passages in the prompt).
        key = (round(f1, 4), round(precision, 4), threshold)
        if best is None or key > best[0]:
            best = (key, threshold, precision, recall, f1)

    _, threshold, precision, recall, f1 = best
    print(f"\nBEST: soglia {threshold:.2f} · precision {precision:.3f} · "
          f"recall {recall:.3f} · F1 {f1:.3f}")
    if args.threshold is not None:
        p, r, f, fp, fn, unrelated = metrics(args.threshold)
        print(f"Alla soglia richiesta {args.threshold:.2f}: precision {p:.3f} · "
              f"recall {r:.3f} · F1 {f:.3f} · FP {fp} · FN {fn} · hit non pertinenti {unrelated}")


if __name__ == "__main__":
    main()
