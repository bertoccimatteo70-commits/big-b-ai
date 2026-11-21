# Build

## Toolchain usata per questa release

| Componente | Versione |
|---|---|
| JDK | 17.0.21.1+2 (Temurin) |
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (KSP 2.0.21-1.0.28) |
| Compile/target SDK | 35 |
| Build tools | 35.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| llama.cpp | v1.0.0, commit `b1b770d90d94cad9e9ea5b993bdbc383a522fb28` |

Il Gradle wrapper è committato (`gradlew`, `gradle/wrapper/`), quindi locale e CI usano la stessa
versione senza dipendere da un Gradle installato nel `PATH`.

## 1. Libreria nativa

```bash
bash scripts/fetch-llama.sh          # clona llama.cpp al commit fissato in third_party/
bash scripts/fetch-bundled-model.sh  # scarica e verifica il modello di embedding (132 MB)
bash scripts/build-native.sh         # compila e strippa libbigbai.so per tutte le ABI
```

`fetch-bundled-model.sh` scarica `multilingual-e5-small-q8_0.gguf` (TwinSunsLLC, Q8_0,
132.439.008 byte) e ne verifica dimensione e SHA-256
(`e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93`) prima di copiarlo in
`app/src/main/assets/models/`. Il file non è versionato: è troppo grande per la storia Git ed è
riproducibile. Senza di esso `assembleDebug` produce un APK incompleto e
`package-debug-apk.sh` lo rifiuta.

`build-native.sh` usa CMake + NDK, `-DGGML_VULKAN=OFF`, `-DGGML_OPENMP=OFF`,
`-DLLAMA_CURL=OFF`, e produce una libreria per ogni ABI spedita:

```
llm/src/main/jniLibs/arm64-v8a/libbigbai.so     (~5,9 MB stripped)
llm/src/main/jniLibs/armeabi-v7a/libbigbai.so   (~4,2 MB stripped)
llm/src/main/jniLibs/x86_64/libbigbai.so        (~6,6 MB stripped)
```

Per compilare un sottoinsieme:

```bash
ABIS="arm64-v8a" bash scripts/build-native.sh
ABIS="arm64-v8a x86_64" JOBS=4 bash scripts/build-native.sh
```

`armeabi-v7a` compila con `GGML_LLAMAFILE=OFF`: i kernel FP16 NEON di llamafile richiedono ARMv8,
mentre i dispositivi 32-bit sono ARMv7. La scelta è nel `CMakeLists.txt` del modulo `llm`.

La build completa delle tre ABI richiede alcuni minuti e circa 1–2 GB di RAM per processo con
`JOBS=2`. Su macchine piccole:

```bash
JOBS=1 bash scripts/build-native.sh
```

Il `.so` è in `.gitignore`: viene ricostruito in CI, non versionato.

### Perché CPU only

La build v1.0.0 di llama.cpp non include gli shader SPIR-V precompilati richiesti dal backend
Vulkan. Distribuire un backend GPU senza shader verificati avrebbe significato una funzione
dichiarata e non funzionante, quindi il backend Vulkan è escluso e l'app lo dichiara nelle
impostazioni. Il percorso CPU è compilato e verificato.

## 2. Build Gradle

```bash
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest \
  :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew --no-daemon jacocoTestReport
./gradlew --no-daemon assembleDebug
bash scripts/package-debug-apk.sh
```

`gradle.properties` è tarato su host con 2 core e ~4 GB di RAM:

```properties
org.gradle.jvmargs=-Xmx2816m -XX:MaxMetaspaceSize=768m
org.gradle.workers.max=2
kotlin.compiler.execution.strategy=in-process
```

Su una macchina più grande si possono alzare questi valori.

## 3. APK di debug

La consegna è un APK di debug, firmato con la chiave debug Android (`CN=Android Debug`):

```bash
./gradlew --no-daemon assembleDebug
bash scripts/package-debug-apk.sh
```

Lo script copia l'APK in `dist/BIG-B-AI-debug-<versione>.apk`, verifica che `libbigbai.so` **e**
`libsqlcipher.so` siano presenti per `arm64-v8a`, `armeabi-v7a` e `x86_64` (un APK senza motore
nativo, o con un'ABI incompleta, non deve mai essere pubblicato), verifica che il modello di
embedding dentro l'APK abbia dimensione e SHA-256 attesi, controlla firma (`apksigner`) e manifest
(`aapt2`: package, versionCode, versionName, ABI), confronta l'hash della copia con quello
dell'APK appena compilato, calcola lo SHA-256 e scrive `dist/BIG-B-AI-debug-<versione>.txt` con
dimensione, data, package e firma. Lo script **fallisce** se l'APK è sotto i **200 MiB**
(209.715.200 byte) richiesti dal progetto.

La variante debug usa `applicationId` `com.bigbai.app.debug` e `versionName` `1.5.0-debug`, quindi
convive con una eventuale build di release sullo stesso dispositivo.

`app/build.gradle.kts` supporta ancora la firma di release (`keystore.properties` o variabili
`SIGNING_*`) per build future, ma non fa parte di questa consegna.

## 4. Test

I test unitari JVM coprono la logica pura, senza Android framework:

- `ConversationSessionTest` (6 test): identità della conversazione e regressione del bug che
  faceva uscire dalla chat al secondo messaggio.
- `StreamingBufferTest` (4 test): throttling e flush dello streaming.
- `LanguageCoachTest` (7 test): glossari del tutor, prompt, parsing e deduplica del lessico.
- `ChatTitleTest` (5 test): derivazione deterministica del titolo, markdown e troncamento.
- `Utf8StreamTest` (6 test): UTF-8 spezzato tra token, emoji, sequenze incomplete.
- `PromptTemplatesTest` (7 test): prompt multilingua, Vision, fonti, tutor e BIG B Code.
- `AttachmentPolicyTest` (5 test): classificazione MIME/estensione e limiti di dimensione.

- `GenerationTimingTest` (5 test): token/s e tempi prompt/TTFT dai contatori llama.cpp e fallback wall-clock.
- `ProjectFilesReadTest` (4 test): limite byte applicato durante la lettura, UTF-8 multi-byte.
- `PatchEngineTest` (8 test): parsing del protocollo patch, path, blocchi non chiusi, diff e statistiche.
- `StaticAnalyzersTest` (10 test): HTML, Java, C++, CSS.
- `HtmlTextTest` (4 test): rimozione script/style, entità, spazi.
- `AnswerSanitizerTest` (5 test): stop marker completi e spezzati, testo trattenuto, casi vuoti.
- `ChatStateTransitionsTest` (3 test): nuova chat e cambio conversazione senza stato ereditato.
- `FileExportTest` (8 test): slug, timestamp, estensioni dal blocco di codice, MIME.
- `ProjectNamingTest` (4 test): nome cartella, HTML iniziale, escaping del titolo.

- `CodeAgentTest` (10), `CodeQuotaTest` (6): protocollo dell'agente, percorsi, limiti, quota.
- `ModelProfilesTest` (7), `ResponseQualityTest` (6), `EvaluationSuiteTest` (5): profili per modello,
  ripetizioni/troncamento, punteggio della suite di valutazione, repetition penalty e min-p.

Nuovi in 1.5.0:

- `EmbeddingsTest` (7), `SemanticIndexTest` (4): chunking con sovrapposizione, similarità, soglia,
  persistenza e rimozione dei vettori.
- `QualityGateTest` (5): ripetizioni, fence non chiusi, tempo limite, una sola riscrittura.
- `CalculatorTest` (8), `ItalianCheckTest` (8): aritmetica verificata, intervalli di anni ignorati,
  spazi/punteggiatura/duplicati, codice escluso.
- `ProjectInsightsTest` (6): duplicati, dipendenze mancanti, `package.json`, report delle modifiche.
- `PromptContextTest` (2): iniezione di passaggi recuperati e calcoli verificati nel prompt.

Totale: **165 test JVM, 0 falliti** (core 42, code 48, llm 59, app 16), eseguiti anche dalla pipeline CI.
Non esistono test strumentali: richiederebbero un device.

## 4.1 Copertura

```bash
./gradlew --no-daemon jacocoTestReport
```

Report aggregato in `build/reports/jacoco/jacocoTestReport/html` (XML e CSV accanto). Misurazione
corrente: **8,0% delle linee** (838 coperte, 9.596 mancanti) e 5,4% dei branch. La copertura è
concentrata sulla logica pura verificabile senza device: `core/chat` 94,7%, `core/lang` 99,1%,
`code` 33,7%, `llm` 23,5%, `app/files` 29,6%. UI Compose, Room, PDF, backup e Keystore richiedono test strumentali e
non sono coperti. Il dato è basso ed è riportato così com'è: non viene presentato come obiettivo
raggiunto.

## Font

`app/src/main/res/font/` contiene Tinos regular/bold/italic/bold-italic. Tinos è distribuito con
licenza Apache-2.0 (testo in `app/src/main/assets/licenses/Tinos-Apache-2.0.txt`) ed è
metricamente compatibile con Times New Roman. Times New Roman è proprietario e non può essere
incluso nell'APK: non sostituirlo con un file scaricato da fonti non autorizzate.


## 5. CI

`.github/workflows/android.yml` (solo build debug):

1. JDK 17, Android SDK con `android-actions/setup-android@v4` (niente pacchetto `tools`, rimosso da
   Google), NDK 27.2.12479018 e CMake 3.22.1;
2. `fetch-llama.sh`, `fetch-bundled-model.sh` (con verifica SHA-256) e `build-native.sh` per
   `arm64-v8a`, `armeabi-v7a` e `x86_64`;
3. test JVM dei quattro moduli (`:core`, `:code`, `:llm`, `:app`);
4. `:app:lintDebug`;
5. `./gradlew assembleDebug`;
6. `package-debug-apk.sh` (verifica ABI, modello, firma, manifest e dimensione ≥ 200 MiB) e upload
   dell'artifact **BIG-B-AI-Debug-APK** (APK + `.sha256` + `.txt`, retention 30 giorni).

La copertura JaCoCo è riproducibile in locale con `./gradlew jacocoTestReport`; la pipeline non
carica un artifact di copertura separato, per non affiancare un secondo artifact a quello APK.

## Problemi comuni

| Sintomo | Causa / soluzione |
|---|---|
| `Could not find com.tom-roush:pdfbox-android:2.0.27.3` | La versione pubblica è `2.0.27.0`; il catalogo versioni è fissato a quella. |
| `Cannot access androidx.room.RoomDatabase` in `:code` o `:app` | `AppDatabase` è API pubblica di `:core`: il modulo consumatore deve avere `room-runtime`. Già configurato. |
| `UnsatisfiedLinkError: libbigbai.so` | La libreria non è stata costruita per quell'ABI. Rieseguire `build-native.sh` e controllare che `libbigbai.so` esista in `llm/src/main/jniLibs/<abi>/`. |
| `apksigner: java: not found` | `JAVA_HOME` non impostato nella shell. Esportare il JDK 17. |
| Build nativa uccisa (OOM) | Ridurre `JOBS` a 1 e chiudere altri processi. |
| `Failed to find package 'tools'` | `android-actions/setup-android@v3` chiedeva il pacchetto `tools`, rimosso dal repository Android. Il workflow usa `v4` e dichiara i pacchetti esplicitamente. |
