# Modelli

## Regola 2.1: nessun modello nell'APK

ITAGPT 2.1 non include pesi GGUF. L'APK contiene codice, risorse e la dipendenza ML Kit per l'OCR;
non contiene Qwen, non contiene il modello di embedding e non contiene modelli alternativi.

Conseguenze dichiarate:

- L'app si installa e si avvia senza modelli.
- Navigazione, impostazioni, Code, OCR, documenti, cronologia, memoria e diagnostica funzionano
  senza un modello.
- **La chat non genera risposte senza un modello.** Mostra un messaggio chiaro e un pulsante verso
  la libreria; non simula risposte e non resta in caricamento.
- Nessun download parte automaticamente. La scelta e l'avvio del download sono dell'utente.

## Modello di embedding (opzionale, non incluso)

| Voce | Valore |
|---|---|
| Modello | `multilingual-e5-small` Q8_0 GGUF (`TwinSunsLLC/multilingual-e5-small-gguf`) |
| File richiesto | `multilingual-e5-small-q8_0.gguf` |
| Dimensione | 132.439.008 byte |
| SHA-256 | `e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93` |
| Licenza | MIT (`intfloat/multilingual-e5-small`; GGUF TwinSunsLLC) |
| Ruolo | retrieval semantico locale: vettori, non generazione di testo |
| Soglia di similarità | 0,83 (misurata in `docs/RETRIEVAL-1.5.1.md`) |
| RAM stimata | 132 MB di pesi + 48 MiB runtime + margine di sicurezza |

Come si abilita:

1. Scarica il file dal publisher (link nella schermata Impostazioni).
2. **Impostazioni → Ricerca semantica → Importa .gguf**.
3. L'app copia il file nella memoria privata e lo accetta solo se dimensione e SHA-256 coincidono.
4. Senza file, la ricerca semantica è disattivata; chat, OCR, documenti e ricerca web restano
   disponibili.

Non esiste un download automatico e nessun file di embedding è nel repository o nell'APK.

## Modelli di chat (solo download esplicito)

Il catalogo è in `llm/src/main/assets/model_catalog.json`. Contiene famiglie Qwen2.5 Apache-2.0 con
hash SHA-256 dei file LFS. I modelli non sono inclusi nell'APK: si scaricano dalla sezione Modelli,
con ripresa e verifica SHA-256.

| Famiglia | Ruolo | Parametri | Quantizzazioni |
|---|---|---|---|
| Qwen2.5 0.5B Instruct | chat | 0.5B | Q2_K, Q3_K_M, Q4_K_M, Q5_K_M, Q6_K |
| Qwen2.5 1.5B Instruct | chat | 1.5B | Q2_K, Q3_K_M, Q4_K_M, Q5_K_M, Q6_K |
| Altre famiglie Qwen2.5 | chat/code/vision | vedi catalogo | vedi catalogo |

La scelta consigliata dipende da RAM, core e stato termico. `PerformanceAdvisor` riduce thread e
contesto quando il dispositivo è caldo o in risparmio energetico; non cambia il modello scelto.

## Qualità linguistica

Il modello 1.5B non è più incluso e non è stato eseguito un benchmark 2.1 su un modello reale.
I report 2.0.0 restano validi solo come storico:

- `docs/ai-eval/qwen2.5-1.5b-instruct-q4_k_m-itagpt-2.0.0.md` — 8/8 casi chiusi con EOS, errori di
  contenuto residui;
- `docs/ai-eval/qwen2.5-0.5b-instruct-q4_k_m-itagpt-2.0.0.md` — 0.5B scartato per ripetizioni.

I prompt 2.1 sono stati rafforzati (italiano, punteggiatura, divieto di inventare fonti, fonti web
con data e sito), ma **non viene dichiarato un miglioramento misurato** senza un modello eseguito.
Un modello da 1,5B resta limitato: non va usato come fonte medica, legale o fattuale senza verifica.

## Licenze e provenienza

- Qwen2.5: Apache-2.0, publisher Alibaba/Qwen.
- multilingual-e5-small: MIT, `intfloat`; GGUF `TwinSunsLLC`.
- Nessun peso è ridistribuito da ITAGPT: né nel repository né nell'APK.
