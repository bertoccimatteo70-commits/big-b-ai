# Valutazione italiana su modello reale — qwen2.5-0.5b-instruct-q4_k_m

Modello: `qwen2.5-0.5b-instruct-q4_k_m.gguf` · motore llama.cpp host (x86_64, CPU) · template ChatML · temperatura 0,55 · max 260–600 token.

Le risposte sono state generate davvero; i controlli automatici sono elencati accanto a ogni risposta. Il giudizio linguistico resta a chi legge: un modello 1,5B non rappresenta i modelli più grandi che l'app può scaricare.

## Grammatica · corta

**Prompt**

```text
Spiega in due frasi la differenza tra «qual è» e «qual'è».
```

**Risposta** (EOS, 9 parole)

```text
"Qual è" significa "che sia", mentre "qual'è" indica "quale".
```

**Controlli**: nessun problema automatico

## Ortografia e accenti

**Prompt**

```text
Correggi gli errori in questa frase e spiega le correzioni: «Non so se piu conveniente partire, perche domani e un giorno importante».
```

**Risposta** (EOS, 51 parole)

```text
La frase corretta è: "Non so se piu conveniente partire, perche domani un giorno importante."

Ricorso delle correzioni:
1. Sfiorare i verbali in maniera errata: "partire" dovrebbe essere "partire" (solo l'una).
2. Eliminare le parenthasi incorrecte ("perche domani un giorno importante"): "domani e un giorno importante" dovrebbe essere "un giorno importantesso".
```

**Controlli**: spazi doppi, forma errata «piu», forma errata «perche»

## Concordanze e tempi verbali

**Prompt**

```text
Racconta al passato prossimo, in terza persona, una breve gita al mare. Mantieni tempi e concordanze coerenti.
```

**Risposta** (LENGTH, 217 parole)

```text
Mi trovavo al mar, con le braccia che non cedevano. Il sole, a circa cinquecento metri di altezza, brillava come un'illuminatazione alante della luna. Le onde si avvicinavano con una velocità strana e forte.

Mi accostavo in modo da non disturbare la musica, l'arco della vela illuminava il suo viso. Il mare era dietro me, un fiume che dava a ogni misterioso sussurro e stridio.

Mi avvicinavo al mare con le mani, ma non esitavo. Il vento che si addormentava in una di quelle tempeste era un'illuminazione nuova da notare.

Mi avvicinavo al mare, ma non esitavo. Il vento che si addormentava in una di quelle tempeste era un'illuminazione nuova da notare.

Mi avvicinavo al mare, ma non esitavo. Il vento che si addormentava in una di quelle tempeste era un'illuminazione nuova da notare.

Mi avvicinavo al mare, ma non esitavo. Il vento che si addormentava in una di quelle tempeste era un'illuminazione nuova da notare.

Mi avvicinavo al mare, ma non esitavo. Il vento che si addormentava in una di quelle tempeste era un'illuminazione nuova da notare.

Mi avvicinavo al mare, ma non esitavo. Il vento che si addormentava in una di quelle tempeste era un'illuminazione nuova da notare.

Mi avvicinavo al mare, ma non esitavo. Il vento che si addormentava in una di quelle tempeste
```

**Controlli**: spazi doppi, frase ripetuta, troncata al limite di token

## Punteggiatura

**Prompt**

```text
Aggiungi la punteggiatura a questa frase senza cambiare le parole: ieri sono andato al mercato ho comprato mele pere e pane poi sono tornato a casa
```

**Risposta** (EOS, 15 parole)

```text
Ieri andai al mercato. Ho comprato mele pere e pane poi sono tornato a casa
```

**Controlli**: nessun problema automatico

## Comprensione

**Prompt**

```text
Testo: «La maggior parte degli adulti ha bisogno di sette-otto ore di sonno per notte. Chi dorme meno di sei ore in modo abituale può accumulare stanchezza.» Quante ore dorme un adulto secondo il testo? Rispondi citando il testo.
```

**Risposta** (EOS, 35 parole)

```text
Il testo dice che una persona ha bisogno di sette-otto ore di sonno per notte. Quindi, se dorme meno di sei ore in modo abituale, possiede sette ore di sonno e ottobre ore di sonno.
```

**Controlli**: nessun problema automatico

## Coerenza · lunga

**Prompt**

```text
Spiega perché il cielo è blu in massimo 150 parole. Non inventare fonti.
```

**Risposta** (EOS, 101 parole)

```text
Il cielo è blu in massimo 150 parole:

1. Il cielo, di nome "sky", si conosciuta nel mezzo del XIX secolo.

2. Inoltre il cielo è blu in massimo 150 anni, secondo le stime della Storia Astronoma di Giovanni Borgia.

3. Quindi i due requisiti sono che il cielo sia blu e che si tratti del mezzo del XIX secolo.

4. Questi risultati hanno contribuito al sviluppo della storia astronoma, che ha iniziato con la stima blu del cielo.

5. Questi risultati sono stati documentati e riconosciuti nel mezzo del XIX secolo, quindi il cielo è blu in massimo 150 parole.
```

**Controlli**: spazi doppi

## Multi-turno

**Prompt**

```text
Qual è la capitale dell'Australia?
La capitale dell'Australia è Canberra.
E quanti abitanti ha più o meno?
```

**Risposta** (EOS, 17 parole)

```text
La capitale dell'Australia, Canberra, ha circa 350.000 abitanti e la città di Sydney ha almeno 27 millionate.
```

**Controlli**: nessun problema automatico

## Matematica

**Prompt**

```text
Quanto fa il 17% di 250? Mostra i passaggi e verifica il risultato.
```

**Risposta** (EOS, 5 parole)

```text
17% di 250 è 42,5.
```

**Controlli**: nessun problema automatico

## Riepilogo

| Caso | Fine | Controlli automatici |
|---|---|---|
| it-grammar | EOS | — |
| it-spelling | EOS | spazi doppi, forma errata «piu», forma errata «perche» |
| it-tenses | LENGTH | spazi doppi, frase ripetuta, troncata al limite di token |
| it-punctuation | EOS | — |
| it-comprehension | EOS | — |
| it-long | EOS | spazi doppi |
| it-multiturn | EOS | — |
| it-math | EOS | — |
