# Valutazione italiana su modello reale — qwen2.5-0.5b-instruct-q4_k_m

Modello: `qwen2.5-0.5b-instruct-q4_k_m.gguf` · motore llama.cpp host (x86_64, CPU) · template ChatML · temperatura 0,55 · max 260–600 token.

Le risposte sono state generate davvero; i controlli automatici sono elencati accanto a ogni risposta. Il giudizio linguistico resta a chi legge: un modello 1,5B non rappresenta i modelli più grandi che l'app può scaricare.

## Grammatica · corta

**Prompt**

```text
Spiega in due frasi la differenza tra «qual è» e «qual'è».
```

**Risposta** (EOS, 15 parole)

```text
"Qual è" e "qual'è" sono parole che usano il stesso concetto, ma differenza in natura.
```

**Controlli**: nessun problema automatico

## Ortografia e accenti

**Prompt**

```text
Correggi gli errori in questa frase e spiega le correzioni: «Non so se piu conveniente partire, perche domani e un giorno importante».
```

**Risposta** (EOS, 125 parole)

```text
La frase corretta è: "Non so se piu conveniente partire, perche domani un giorno importante."

Cerco di clarificare i passaggi:

1. **Il punto:** Non so se piu conveniente partire, perche domani un giorno importante.

2. **La frase:** "perche" (quindi) è incompleto e necessitava di essere corrispondente con il seguente passaggio: "domani un giorno importante".

3. **La frase:** "perche" (quindi) non è più appropriato per la condizione che si parla di.

4. **Il punto:** Non so se piu conveniente partire, perche domani un giorno importante.

La frase corretta esattamente è: "Non so se piu conveniente partire, perche domani un giorno importante."

Questo errore si riscontra nella seconda frase che non ha corrispondenza con il seguente passaggio, dove "domani un giorno importante" è compreso come una condizione.
```

**Controlli**: spazi doppi, forma errata «piu», forma errata «perche», frase ripetuta

## Concordanze e tempi verbali

**Prompt**

```text
Racconta al passato prossimo, in terza persona, una breve gita al mare. Mantieni tempi e concordanze coerenti.
```

**Risposta** (EOS, 57 parole)

```text
Mi sono portato con me una cura, un biciclo e un paio di vestiti. Poi ho fatto il giro al mare verso la mia casa. Il mio compito fu mettere le due macchine in movimento e fare un'occhiata intorno. Ho trovato l'albergo dove mi avrei arruolato, mi sono alzato in fretta e mi sono portato verso casa.
```

**Controlli**: nessun problema automatico

## Punteggiatura

**Prompt**

```text
Aggiungi la punteggiatura a questa frase senza cambiare le parole: ieri sono andato al mercato ho comprato mele pere e pane poi sono tornato a casa
```

**Risposta** (EOS, 16 parole)

```text
ieri sono andato al mercato ha comprato mele pere e pane poi sono tornato a casa
```

**Controlli**: nessun problema automatico

## Comprensione

**Prompt**

```text
Testo: «La maggior parte degli adulti ha bisogno di sette-otto ore di sonno per notte. Chi dorme meno di sei ore in modo abituale può accumulare stanchezza.» Quante ore dorme un adulto secondo il testo? Rispondi citando il testo.
```

**Risposta** (EOS, 34 parole)

```text
Il testo dice che una persona ha bisogno di sette-otto ore di sonno per notte. Quindi, se dorme meno di sei ore in modo abituale, il suo sonno sarebbe di sette ore e ottobre.
```

**Controlli**: nessun problema automatico

## Coerenza · lunga

**Prompt**

```text
Spiega perché il cielo è blu in massimo 150 parole. Non inventare fonti.
```

**Risposta** (LENGTH, 308 parole)

```text
Il cielo è blu in massimo 150 parole:

1. Il cielo ha una scintilla di blu.
2. Ci sono due principali colori del cielo: l'azzurro e il grigio.
3. L'azurato è più visibile all'esterno, mentre i grigi sono più visibili dentro.
4. Ci sono nuovi colori del cielo che non esistevano in passato (come la neve).
5. Il blu è un'ottica fisica, rendendo il cielo più visibile.
6. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i radar).
7. Il blu è un'ottica sensoriale, rendendo il cielo più visibile.
8. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i GPS).
9. Il blu è un'ottica astronome, rendendo il cielo più visibile.
10. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i satelliti).
11. Il blu è un'ottica astronome, rendendo il cielo più visibile.
12. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i satellite).
13. Il blu è un'ottica astronome, rendendo il cielo più visibile.
14. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i satelliti).
15. Il blu è un'ottica astronome, rendendo il cielo più visibile.
16. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i satellite).
17. Il blu è un'ottica astronome, rendendo il cielo più visibile.
18. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i satelliti).
19. Il blu è un'ottica astronome, rendendo il cielo più visibile.
20. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i satelliti).
21. Il blu è un'ottica astronome, rendendo il cielo più visibile.
22. Ci sono nuovi strumenti e tecnologie che non esistevano in passato (come i satellite).
23. Il blu è un'ottica astronome, rendendo il cielo più visibile.
24. Ci sono nuovi strumenti e tecn
```

**Controlli**: spazi doppi, frase ripetuta, troncata al limite di token

## Multi-turno

**Prompt**

```text
Qual è la capitale dell'Australia?
La capitale dell'Australia è Canberra.
E quanti abitanti ha più o meno?
```

**Risposta** (EOS, 9 parole)

```text
La capitale dell'Australia, Canberra, ha circa 3 million abitanti.
```

**Controlli**: nessun problema automatico

## Matematica

**Prompt**

```text
Quanto fa il 17% di 250? Mostra i passaggi e verifica il risultato.
```

**Risposta** (EOS, 12 parole)

```text
Il 17% di 250 è:
\[ 250 \times 0.17 = 42,5 \]
```

**Controlli**: spazi doppi

## Riepilogo

| Caso | Fine | Controlli automatici |
|---|---|---|
| it-grammar | EOS | — |
| it-spelling | EOS | spazi doppi, forma errata «piu», forma errata «perche», frase ripetuta |
| it-tenses | EOS | — |
| it-punctuation | EOS | — |
| it-comprehension | EOS | — |
| it-long | LENGTH | spazi doppi, frase ripetuta, troncata al limite di token |
| it-multiturn | EOS | — |
| it-math | EOS | spazi doppi |
