# BIG B AI 1.4.0 — Audit di intelligenza, autonomia, UX e prestazioni

Data: 21 novembre 2026. Versione `1.4.0` (versionCode 5), variante debug
`com.bigbai.app.debug`.

Questo documento descrive cosa è stato cambiato nel codice, cosa è stato misurato e
cosa **non** è stato possibile verificare in questo ambiente. Le affermazioni non
verificate sono indicate come tali.

---

## 1. BIG B Code: agente con strumenti reali

La versione 1.3.0 proponeva modifiche tramite blocchi `<<<<<<< FILE:` e richiedeva
un'approvazione esplicita per ogni file. Era un assistente di suggerimenti, non un
agente.

La 1.4.0 introduce un ciclo di esecuzione reale:

1. l'utente descrive un obiettivo («Crea un'app per gestire le attività»);
2. il modello riceve un system prompt con il protocollo degli strumenti, l'albero
   del progetto e un estratto dei file;
3. il modello emette uno o più strumenti;
4. l'app **esegue** gli strumenti sulla cartella autorizzata;
5. il risultato reale di ogni strumento viene restituito al modello;
6. il ciclo continua (massimo 5 passi) finché il modello chiama `finish` o
   consegna una risposta senza strumenti;
7. i file scritti vengono analizzati con gli analizzatori statici e il risultato è
   mostrato nell'activity log.

### Protocollo

```
<tool name="write_file" path="index.html">
contenuto completo
</tool>
<tool name="read_file" path="src/app.js"/>
<tool name="list_files"/>
<tool name="delete_file" path="vecchio.txt"/>
<tool name="finish">riassunto</tool>
```

Il protocollo è testuale e non JSON perché i modelli locali piccoli producono JSON
malformato con frequenza alta; un blocco delimitato è recuperabile anche quando il
modello lo avvolge in un fence Markdown. I vecchi blocchi `<<<<<<< FILE:` restano
supportati come fallback.

### Limiti e protezioni (implementati e testati)

| Regola | Valore |
|---|---|
| Passi per attività | 5 |
| Strumenti per passo | 6 |
| File distinti per attività | 40 |
| Dimensione massima di un file scritto | 200.000 byte |
| Contesto progetto inviato al modello | 24.000 caratteri |
| Lettura massima per file | 12.000 caratteri |
| Percorsi assoluti, `..`, file nascosti | rifiutati (`AgentAction.Invalid`) |
| Blocco non chiuso | rifiutato: non viene scritto mezzo file |
| Eliminazioni | richiedono conferma esplicita nell'interfaccia |

Prima della prima scrittura viene creato un checkpoint Git del progetto, così una
modifica dell'agente è recuperabile. Ogni scrittura, lettura, errore e controllo
compare nell'activity log con stato **completato / non riuscito / richiede te**.

L'agente non può affermare di aver compilato: sul dispositivo non esiste un
compilatore Java/C++; l'app esegue solo analisi statica e la sandbox JavaScript per
l'HTML. Il prompt lo dichiara e l'app non mostra mai un risultato di build inventato.

---

## 2. Limite di 5 richieste al giorno

- Una **attività richiesta dall'utente** consuma una richiesta.
- Letture, scritture, correzioni, controlli e passi interni **non** consumano.
- Il contatore è persistente (DataStore) e si azzera al cambio di giorno locale.
- Un errore tecnico prima di qualsiasi output del modello rimborsa la richiesta.
- Il numero residuo è visibile nel menu laterale, nella schermata di BIG B Code e
  nella barra del progetto; a zero il pulsante di invio è disabilitato.

**Limite dichiarato:** non esiste account né backend. Il conteggio è valido solo su
questo dispositivo: disinstallare l'app o cancellarne i dati lo azzera. Non è un
limite globale per utente e non viene presentato come tale.

---

## 3. Qualità delle risposte

### Profili per modello

`ModelProfiles` sceglie temperatura, top-p, top-k, budget di risposta e limite di
contesto in base a famiglia e dimensione del modello (coder, tiny, small, qwen,
llama, gemma, phi, mistral, generico). I modelli piccoli ricevono un'istruzione di
concisione e la dichiarazione esplicita dei propri limiti.

Le impostazioni manuali di temperatura/top-p/top-k hanno effetto solo con
«Impostazioni avanzate» sbloccate: altrimenti il profilo del modello resta
autorevole. La regola è visibile nell'interfaccia.

### Prompt

Il system prompt della chat ora impone:

- risposta diretta nella prima frase, dettagli in punti brevi;
- nessuna ripetizione della domanda o della stessa frase;
- lunghezza proporzionata (3–6 frasi per una domanda semplice, sezioni per una
  tecnica);
- dichiarazione dell'interpretazione scelta quando la domanda è ambigua.

### Valutazione ripetibile

`EvaluationSuite` contiene 7 casi (spiegazione semplice, spiegazione tecnica,
riassunto, conoscenza generale, conversazione, programmazione, documento) e
punteggi deterministici: lunghezza minima, termini obbligatori, ripetizioni,
troncamento, fence non chiusi. `ResponseQuality` misura le 6-gram ripetute e
segnala risposte vuote, troncate o in loop.

I test JVM verificano il punteggio con risposte finte. **L'esecuzione della suite
contro un modello reale non è stata fatta**: richiede un dispositivo con un modello
installato, assente in questo ambiente.

---

## 4. Modello della chat e modello di BIG B Code

Sono due scelte indipendenti:

- `activeModelId` per la chat;
- `activeCodeModelId` per BIG B Code.

Nella scheda «Modelli installati» ogni modello ha «Usa in chat» e «Usa in BIG B
Code». Se non viene scelto un modello per il codice, l'app usa il primo modello con
ruolo CODE e lo dichiara nella barra («… (automatico)»). Non c'è più il cambio
silenzioso della 1.3.0, dove «Usa» impostava entrambi.

---

## 5. Risposta completa, non in streaming

`ChatViewModel` accumula i token in memoria e **non** aggiorna lo stato a ogni
token. La risposta compare nella chat solo quando è completa, con l'animazione di
inserimento della lista. Durante la generazione si vede un indicatore animato.

- Annullamento: la parte prodotta viene salvata con la dicitura
  «(risposta interrotta)».
- Errore: la risposta parziale viene scartata e si mostra l'errore; non viene
  presentata come definitiva.
- Timeout: 180 secondi per la chat, 240 per BIG B Code; allo scadere il motore
  nativo viene fermato e l'esito è dichiarato.
- Memoria: il buffer è una `StringBuilder`; non viene ricomposto nulla durante la
  generazione, quindi il costo di Compose per token è zero.

---

## 6. Font

Times New Roman è proprietario (Monotype) e non può essere redistribuito in un
APK. L'app include **Tinos** (Apache-2.0), metricamente compatibile con Times New
Roman: stesse larghezze e proporzioni. I quattro stili (regular, bold, italic,
bold-italic) sono in `app/src/main/res/font/` e la licenza è in
`app/src/main/assets/licenses/`.

Tinos è applicato alla tipografia Material (tutti i ruoli: titoli, corpo, label) e
quindi a messaggi, descrizioni e testi principali. Il codice resta monospaziato. Le
bolle restano bianche `#FFFFFF` con testo nero `#000000` anche in modalità scura.

---

## 7. Animazioni e accessibilità

- Transizioni tra schermate (fade + slide) già presenti, ora disattivate anche
  quando l'utente ha ridotto le animazioni a livello di sistema Android
  (`ANIMATOR_DURATION_SCALE == 0`), oltre che con l'opzione dell'app.
- Comparsa dei messaggi tramite `animateItem()` della lista.
- Indicatore di elaborazione a tre punti con onda di opacità; statico con
  animazioni ridotte.
- Progresso dei download interpolato con `animateFloatAsState`.
- Menu laterale: animazione Material del drawer.

Le animazioni non sono state osservate su un dispositivo: la verifica è a livello
di codice e di compilazione.

---

## 8. Prestazioni

Modifiche misurate o verificabili nel codice:

- eliminato l'aggiornamento di stato per ogni token nella chat (prima ogni token
  ricomponeva l'intera schermata);
- contesto del progetto limitato a 24.000 caratteri e 24 file;
- massimo 5 passi e 6 strumenti per passo nell'agente;
- letture dei file su `Dispatchers.IO` con limite in byte durante la lettura;
- nessuna nuova dipendenza.

Non sono state eseguite misure di RAM, CPU, tempo di avvio o token/s su hardware
reale: sarebbero stime, non misure.

---

## 9. Test e verifiche eseguite

| Verifica | Esito |
|---|---|
| Test JVM | **124, 0 falliti** (core 26, code 42, llm 40, app 16) |
| Lint debug | **0 errori**, 66 warning (dipendenze/AGP non aggiornate) |
| Compilazione debug | BUILD SUCCESSFUL |
| Librerie native | 3 ABI presenti nell'APK, compilate da sorgenti llama.cpp |
| Font nell'APK | 4 file Tinos presenti in `res/font/` |
| APK | **115.341.878 byte = 110,00 MiB = 115,34 MB** |
| SHA-256 | `bc54c237439581063cad3a6374fe1ef25db7beb8484f40d2d0038ba3dcd1f67b` |
| versionCode / versionName | 5 / `1.4.0-debug` |

Nuovi test aggiunti (33):

- `CodeAgentTest` (10): parsing write/read/list/delete/finish, blocchi multipli,
  formato legacy, traversal e percorsi assoluti, blocco non chiuso, fence Markdown,
  limite di azioni, file troppo grande, prosa senza strumenti.
- `CodeQuotaTest` (6): reset giornaliero, consumo, esaurimento, rimborso, clamp,
  fuso orario.
- `ModelProfilesTest` (6): coder, tiny, differenza qwen/llama, override avanzato,
  budget dei modelli piccoli, suggerimento di stile.
- `ResponseQualityTest` (6): vuoto, risposta normale, loop, fence non chiuso,
  troncamento, eco della domanda.
- `EvaluationSuiteTest` (5): punteggio completo, risposta breve, termini mancanti,
  risposta vuota, esecuzione della suite.

---

## 10. Cosa non è stato verificato

- Avvio dell'app e utilizzo reale dell'interfaccia: nessun dispositivo o emulatore.
- Inferenza reale e qualità delle risposte di un modello: nessun modello installato.
- Download di un modello, SAF, Keystore, notifiche, sandbox su device.
- Persistenza della quota attraverso un riavvio reale del processo: la logica e il
  formato persistito sono testati, il ciclo su device no.
- Animazioni e font a schermo: verificati solo come risorse e codice compilato.
- Misure di RAM/CPU/token al secondo su hardware reale.

Questi punti sono riportati anche in `docs/LIMITATIONS.md`.
