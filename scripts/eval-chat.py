#!/usr/bin/env python3
"""Runs the Italian quality prompts against a real chat model.

The model is executed by build/llama-host/bin/eval_chat (same llama.cpp code
used on device, CPU, ChatML template as Qwen2.5). The output is written to a
Markdown report with a few objective checks; the linguistic judgement stays
with the reader, because a small 1.5B model is not a substitute for a human
review.

Usage:
    python3 scripts/eval-chat.py models/qwen2.5-1.5b-instruct-q4_k_m.gguf
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BINARY = os.path.join(ROOT, "build", "llama-host", "bin", "eval_chat")
OUT_DIR = os.path.join(ROOT, "docs", "ai-eval")

# Stesso prompt compatto usato da PromptTemplates per i modelli sotto 1B.
SYSTEM = """Sei AI, l'assistente locale di ITAGPT. Rispondi in italiano corretto, chiaro e breve. Usa solo fatti certi: se non sai, dillo. Non inventare fonti, azioni o funzionalità. Non ripetere la domanda. Usa Markdown per elenchi e codice. Per la matematica mostra i passaggi."""

CASES = [
    ("Grammatica · corta", "it-grammar",
     [("user", "Spiega in due frasi la differenza tra «qual è» e «qual'è».")], 300),
    ("Ortografia e accenti", "it-spelling",
     [("user", "Correggi gli errori in questa frase e spiega le correzioni: "
               "«Non so se piu conveniente partire, perche domani e un giorno importante».")], 400),
    ("Concordanze e tempi verbali", "it-tenses",
     [("user", "Racconta al passato prossimo, in terza persona, una breve gita al mare. "
               "Mantieni tempi e concordanze coerenti.")], 400),
    ("Punteggiatura", "it-punctuation",
     [("user", "Aggiungi la punteggiatura a questa frase senza cambiare le parole: "
               "ieri sono andato al mercato ho comprato mele pere e pane poi sono tornato a casa")], 260),
    ("Comprensione", "it-comprehension",
     [("user", "Testo: «La maggior parte degli adulti ha bisogno di sette-otto ore di sonno "
               "per notte. Chi dorme meno di sei ore in modo abituale può accumulare stanchezza.» "
               "Quante ore dorme un adulto secondo il testo? Rispondi citando il testo.")], 300),
    ("Coerenza · lunga", "it-long",
     [("user", "Spiega perché il cielo è blu in massimo 150 parole. Non inventare fonti.")], 600),
    ("Multi-turno", "it-multiturn",
     [("user", "Qual è la capitale dell'Australia?"),
      ("assistant", "La capitale dell'Australia è Canberra."),
      ("user", "E quanti abitanti ha più o meno?")], 300),
    ("Matematica", "it-math",
     [("user", "Quanto fa il 17% di 250? Mostra i passaggi e verifica il risultato.")], 400),
]


def chatml(turns):
    text = f"<|im_start|>system\n{SYSTEM}<|im_end|>\n"
    for role, content in turns:
        text += f"<|im_start|>{role}\n{content}<|im_end|>\n"
    return text + "<|im_start|>assistant\n"


def checks(answer):
    issues = []
    if "  " in answer.replace("\n", " "):
        issues.append("spazi doppi")
    for wrong in (" piu ", " perche ", " puo ", " qual'è", " un pò"):
        if wrong in answer:
            issues.append(f"forma errata «{wrong.strip()}»")
    sentences = [s.strip().lower() for s in re.split(r"[.!?]\s+", answer) if len(s.strip()) > 20]
    if len(sentences) != len(set(sentences)):
        issues.append("frase ripetuta")
    return issues


def main():
    model = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "models", "qwen2.5-1.5b-instruct-q4_k_m.gguf")
    if not os.path.isfile(BINARY):
        sys.exit("eval_chat assente: compilalo con g++ (vedi scripts/eval_chat.cpp)")
    os.makedirs(OUT_DIR, exist_ok=True)
    name = os.path.basename(model).replace(".gguf", "")
    report = [f"# Valutazione italiana su modello reale — {name}",
              "",
              f"Modello: `{os.path.basename(model)}` · motore llama.cpp host (x86_64, CPU) · "
              "template ChatML · temperatura 0,55 · max 260–600 token.",
              "",
              "Le risposte sono state generate davvero; i controlli automatici sono elencati "
              "accanto a ogni risposta. Il giudizio linguistico resta a chi legge: un modello "
              "1,5B non rappresenta i modelli più grandi che l'app può scaricare.",
              ""]
    summary = []
    for title, slug, turns, n_predict in CASES:
        prompt_path = os.path.join("/tmp", f"prompt-{slug}.txt")
        with open(prompt_path, "w") as handle:
            handle.write(chatml(turns))
        completed = subprocess.run(
            [BINARY, model, prompt_path, str(n_predict), "0.7"],
            capture_output=True, text=True, check=True,
        )
        output = completed.stdout
        finish = "?"
        match = re.search(r"\[\[tokens=(\d+) finish=(\w+)\]\]", output)
        if match:
            finish = match.group(2)
            output = output[:match.start()].rstrip()
        issues = checks(output)
        if finish == "LENGTH":
            issues.append("troncata al limite di token")
        summary.append((slug, finish, issues))
        report += [f"## {title}", "", "**Prompt**", "", "```text",
                   "\n".join(content for _, content in turns), "```", "",
                   f"**Risposta** ({finish}, {len(output.split())} parole)", "", "```text",
                   output, "```", "",
                   "**Controlli**: " + (", ".join(issues) if issues else "nessun problema automatico"),
                   ""]

    report += ["## Riepilogo", "", "| Caso | Fine | Controlli automatici |", "|---|---|---|"]
    for slug, finish, issues in summary:
        report.append(f"| {slug} | {finish} | {', '.join(issues) if issues else '—'} |")
    report.append("")

    path = os.path.join(OUT_DIR, f"{name}-itagpt-2.0.0.md")
    with open(path, "w") as handle:
        handle.write("\n".join(report))
    print("\n".join(report[-len(summary) - 4:]))
    print(f"\nReport: {path}")


if __name__ == "__main__":
    main()
