# BIG B AI 1.2.0 — Audit, hardening e consegna

Data: 21 novembre 2026. Questo documento copre il secondo passaggio di revisione
(1.1.0 → 1.2.0). L'audit 1.1.0 resta in [AUDIT-1.1.md](AUDIT-1.1.md).

## 1. Metodo e ambiente

Revisione del percorso completo UI → ViewModel → repository Room/SQLCipher →
motore Kotlin → JNI/llama.cpp → filesystem/SAF, più catalogo modelli, download,
backup, BIG B Code, sandbox WebView, manifest, dipendenze, workflow CI e test.

Ambiente di build e verifica:

| Voce | Valore |
|---|---|
| Host | VM Linux x86_64, kernel 6.1.102, 8 vCPU, 15 GiB RAM |
| JDK | Eclipse Adoptium 17.0.21.1 |
| Gradle | 8.11.1 |
| Kotlin | 2.0.21 (plugin), compilatore 2.0.20 |
| Android SDK | 35, build-tools 35.0.0 |
| NDK / CMake | 27.2.12479018 / 3.22.1 |
| Dispositivo / emulatore | **assente** |

Nessuna metrica runtime Android è stata inventata: le voci non misurabili sono
marcate **NON MISURATA**.

## 2. Difetti trovati e corretti in 1.2.0

| # | Difetto | Effetto | Correzione |
|---|---|---|---|
| 1 | Il messaggio utente veniva salvato **prima** della verifica Vision e della costruzione del prompt | Se il modello non era multimodale (o il prompt falliva), il draft tornava nel composer ma il messaggio restava in chat: un retry **duplicava il turno** | Validazione di immagine, capacità Vision, ricerca e prompt prima di `appendMessage`; flag `userMessagePersisted`; il draft torna solo se il turno non è stato salvato |
| 2 | La cronologia per il prompt veniva letta da `state.messages` | Dopo morte del processo, un invio immediato poteva perdere la cronologia non ancora emessa dal collector Room | Lettura autorevole con `ChatRepository.messagesNow(conversationId)` |
| 3 | `loadVisionInput` decodificava su `Dispatchers.Default` e non riciclava le bitmap in caso di errore | Picco di memoria e leak di bitmap su immagini corrotte/overflow | Decodifica e conversione su `Dispatchers.IO`, `try/finally` con riciclo di originale e scalata, controllo dimensione e `OutOfMemoryError` gestito |
| 4 | Nessun limite di dimensione per immagini e PDF; il testo PDF non era limitato | Un PDF enorme poteva gonfiare RAM, JSON Room e backup | Immagini ≤ 24 MB, PDF ≤ 32 MB, massimo 400 pagine e 120.000 caratteri estratti; flag `truncated` |
| 5 | `BackupManager.restore` usava `readBytes()` senza limite | Un backup malevolo/corrotto poteva esaurire la RAM | Lettura bounded a 64 MB con rifiuto prima dell'allocazione |
| 6 | Notifiche chiamate senza controllo esplicito di `POST_NOTIFICATIONS` (3 errori lint) | Rischio `SecurityException` su Android 13+ | Controllo inline di permesso e versione prima di ogni `notify` |
| 7 | `JsSandbox` incrementava un `Int` da thread WebView diversi | Data race sul contatore delle richieste bloccate | `AtomicInteger` |
| 8 | Progresso download: su ripresa veniva contata due volte la parte già presente | Percentuale oltre il 100% nei record | `(alreadyCompleted - existing) + sink.length()` |
| 9 | Allocazione di un nuovo `Json` per ogni messaggio letto e per il catalogo | GC inutile su cronologie lunghe | Istanze condivise |
| 10 | Icona `OpenInNew` deprecata, controllo `SDK_INT >= 29` ridondante con minSdk 31, `databaseEnabled` deprecato | Warning di compilazione/lint | Forma auto-mirrored, controllo rimosso, setting deprecato rimosso |
| 11 | Metadata APK ancora a 39 test e audit 1.1 | Documentazione incoerente | Script aggiornato a 62 test e audit 1.2 |

Restano validi i fix 1.1.0: identità conversazione separata dalla navigazione
(`ConversationSession`), `has_vision` come capacità del modello, reload su
cambio thread/batch, budget RAM live, PSS reale, stop durante il prompt,
throttling streaming a 40 ms, tutor linguistico in-context.

## 3. Test

Eseguiti con:

```bash
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest \
  :llm:testDebugUnitTest :app:testDebugUnitTest
```

| Modulo | Test | Falliti |
|---|---:|---:|
| core | 26 | 0 |
| code | 18 | 0 |
| llm | 13 | 0 |
| app | 5 | 0 |
| **Totale** | **62** | **0** |

Nuovi test 1.2.0:

- `AttachmentPolicyTest` (5): MIME/estensione, formati non supportati, limiti 24/32 MB, dimensione sconosciuta.
- `Utf8StreamTest` (6): caratteri a 2/3/4 byte spezzati tra token, sequenze incomplete, testo misto.
- `PromptTemplatesTest` (7): italiano, spagnolo, Vision disponibile/non disponibile, fonti, tutor, prompt Code.
- `ChatTitleTest` (5): markdown, code block, troncamento a parola, input vuoto.

Nessun test strumentale (`androidTest`) è stato eseguito: richiede device/emulatore.
La copertura con JaCoCo **non è misurata** (non configurata).

## 4. Build, APK e lint

| Voce | Risultato |
|---|---|
| `assembleDebug` | **BUILD SUCCESSFUL** |
| APK | `dist/BIG-B-AI-debug-1.2.0.apk` |
| Dimensione | **92.821.934 byte** (88,5 MiB) |
| Delta vs 1.1.0 (92.815.874) | **+6.060 byte** |
| SHA-256 | `7b8a5a390d05034ef4bb0534657e78fb959e8b2a79128ef3768fe68598d5d808` |
| Firma | `CN=Android Debug`, verificata con `apksigner` |
| Package | `com.bigbai.app.debug`, versionCode 3, versionName 1.2.0-debug |
| minSdk / targetSdk | 31 / 35 |
| ABI | arm64-v8a (`libbigbai.so` 5.945.248 byte, `libsqlcipher.so`) |
| Lint debug | **0 errori, 70 warning** |

Warning lint residui: 54 `GradleDependency` + 6 `AndroidGradlePluginVersion`
(versioni disponibili più recenti, non aggiornate in questo passaggio per non
cambiare toolchain senza device), 5 `TrustAllX509TrustManager` **dentro jar di
terze parti** (BouncyCastle/JGit; nessun trust-all nel codice dell'app),
2 `MonochromeLauncherIcon`, 1 `ObsoleteSdkInt` (cartella mipmap `-v26`), 1
`Untranslatable`, 1 `ChromeOsAbiSupport` (atteso: solo arm64).

Tempi misurati sull'host: build incrementale finale con lint **1m 06s**; run dei
soli test con cache **17s**. La prima build ha incluso il download di Gradle e
non è un dato rappresentativo.

## 5. Metriche runtime

| Metrica | Valore | Note |
|---|---|---|
| Avvio a freddo/caldo | **NON MISURATA** | nessun device/emulatore |
| Apertura chat / cronologia | **NON MISURATA** | idem |
| Caricamento modello | **NON MISURATA** | idem |
| TTFT / token/s | **NON MISURATA** | idem |
| Frame time / fps streaming | **NON MISURATA** | idem |
| CPU / RAM / memoria nativa / GC | **NON MISURATA** | idem |
| Batteria / temperatura | **NON MISURATA** | idem |
| Tempo allegati / accesso DB | **NON MISURATA** | idem |
| Crash / ANR | **NON MISURATI** | nessuna esecuzione su device |

L'APK è compilato, firmato e verificato staticamente; **non** è stato avviato.

## 6. Sicurezza e privacy

- Database Room con SQLCipher, passphrase casuale a 256 bit protetta da Android Keystore.
- `allowBackup=false` e regole di estrazione che escludono root, database, sharedpref e file.
- Backup portabili cifrati AES-256-GCM con PBKDF2-HMAC-SHA256 (150.000 iterazioni); ora limitati a 64 MB.
- Modelli scaricati solo via HTTPS e verificati con SHA-256 prima della promozione a file finale.
- Nessun log applicativo con contenuti utente: la ricerca `Log.*` trova solo esempi in `third_party/llama.cpp`.
- WebView sandbox: JS abilitato solo per l'anteprima, storage/file/content disabilitati, rete bloccata salvo opt-in esplicito, bridge limitato a log/error, timeout e `destroy()`.
- BIG B Code non esegue Java/C++: analisi statica dichiarata come tale.
- Limite residuo: la chiave Brave Search è salvata nel DataStore privato dell'app (non cifrata a livello applicativo, esclusa da backup). Non viene loggata né inclusa nei backup.

## 7. Compatibilità

- minSdk 31, targetSdk 35, solo arm64-v8a: non allargati in questo passaggio.
- Nessun dispositivo fisico, emulatore o ChromeOS/x86 disponibile: compatibilità runtime **non verificata**.
- Orientamento e ridimensionamento non provati a runtime; `MainActivity` gestisce le configurazioni senza ricreazione.
- Nessuna modifica a permessi, ABI o requisiti hardware.

## 8. Funzioni ancora incomplete o non verificate

- Nessun OCR: i PDF scansionati sono rifiutati con messaggio esplicito.
- Nessun compilatore Java/C++ sul dispositivo; solo analisi statica.
- Il tutor linguistico non riaddestra i pesi: apprendimento in-context + lessico persistente.
- Nessun training, fine-tuning o download di adapter.
- Paging non introdotto; chat molto lunghe caricano tutte le righe della conversazione aperta.
- GPU/Vulkan non inclusa; inferenza CPU.
- R8/minify disattivato; release non firmata di produzione (fuori consegna).
- UI localizzata solo in italiano; il selettore lingua governa le risposte del modello.
- Nessun test su device: avvio, inferenza, Vision, download, SAF, Keystore, notifiche e WebView restano **implementati e compilati, non verificati a runtime**.
- Baseline Profiles non inclusi nella variante debug (AGP non li pacchettizza).

## 9. CI

Il workflow [.github/workflows/android.yml](../.github/workflows/android.yml) mantiene
nome artifact `BIG-B-AI-Debug-APK`, `actions/upload-artifact@v4`, retention 30 giorni
e contenuto APK + SHA-256. L'unica modifica è l'esecuzione dei test anche per
`llm` e `app`. La pipeline compila il nativo, esegue i 62 test, assembla l'APK,
verifica la presenza di `libbigbai.so`, calcola lo SHA-256 e carica l'artifact.

Lo stato della run e il link all'artifact sono riportati nel messaggio di consegna
solo dopo la pubblicazione effettiva su GitHub.
