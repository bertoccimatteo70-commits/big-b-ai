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

## 1. Modelli inclusi

```bash
bash scripts/fetch-bundled-model.sh  # embedding multilingual-e5-small (132 MB)
bash scripts/fetch-bundled-chat.sh   # chat Qwen2.5 1.5B Q4_K_M (1.117 MB)
```

`fetch-bundled-chat.sh` scarica `qwen2.5-1.5b-instruct-q4_k_m.gguf` dal repository
`Qwen/Qwen2.5-1.5B-Instruct-GGUF` (Apache-2.0) e verifica **dimensione e SHA-256**
(`6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`) prima di copiarlo in
`app/src/main/assets/models/`. Se il file esiste già e l'hash coincide, non viene riscaricato.

I due `.gguf` non sono versionati: sono grandi e riproducibili. Entrambi gli script falliscono se il
file scaricato è corrotto; `package-debug-apk.sh` e `package-release-apk.sh` verificano poi
dimensione e SHA-256 **dentro l'APK**. Senza uno dei due modelli l'APK viene rifiutato.

## 2. Libreria nativa

```bash
bash scripts/fetch-llama.sh          # clona llama.cpp al commit fissato in third_party/
bash scripts/build-native.sh         # compila e strippa libbigbai.so per tutte le ABI
```

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

Il `.so` è in `.gitignore`: viene ricostruito in CI, non versionato.

### Perché CPU only

La build v1.0.0 di llama.cpp non include gli shader SPIR-V precompilati richiesti dal backend
Vulkan. Distribuire un backend GPU senza shader verificati avrebbe significato una funzione
dichiarata e non funzionante, quindi il backend Vulkan è escluso e l'app lo dichiara nelle
impostazioni. Il percorso CPU è compilato e verificato.

## 3. Build Gradle

```bash
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest \
  :llm:testDebugUnitTest :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon assembleDebug
bash scripts/package-debug-apk.sh
```

`gradle.properties` usa 4 GB di heap per il processo Gradle, due worker e il compilatore Kotlin
in-process. Il valore è stato alzato perché il packaging dell'asset da 1,1 GB durante
`assembleRelease` non entri nei limiti precedenti:

```properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1g
org.gradle.workers.max=2
kotlin.compiler.execution.strategy=in-process
```

Su una macchina con più RAM si può alzare `-Xmx`; su una più piccola usare `JOBS=1` per la parte
nativa e non eseguire debug e release in parallelo.

## 4. APK di debug

```bash
./gradlew --no-daemon assembleDebug
bash scripts/package-debug-apk.sh
```

Lo script copia l'APK in `dist/ITAGPT-debug-<versione>.apk`, verifica che `libbigbai.so` **e**
`libsqlcipher.so` siano presenti per `arm64-v8a`, `armeabi-v7a` e `x86_64`, che il modello di
embedding e quello di chat abbiano dimensione e SHA-256 attesi **dentro l'APK**, la firma
(`apksigner`) e il manifest (`aapt2`: package, versionCode, versionName, ABI). Confronta l'hash
della copia con quello dell'APK appena compilato, calcola lo SHA-256 e scrive
`dist/ITAGPT-debug-<versione>.txt` con dimensione, data, package, firma e modelli.

La variante debug usa `applicationId` `com.bigbai.app.debug` e `versionName` `2.0.0-debug`, quindi
convive con una eventuale build di release sullo stesso dispositivo.

Non esiste alcuna soglia minima o massima di dimensione: lo script fallisce solo se un componente
reale (ABI, modello, firma, manifest o checksum) è sbagliato.

## 5. APK di release, R8 e firma

```bash
./gradlew --no-daemon assembleRelease
bash scripts/package-release-apk.sh
```

`app/build.gradle.kts` abilita per la release:

- `isMinifyEnabled = true` (R8);
- `isShrinkResources = true`;
- regole keep in `app/proguard-rules.pro` per JNI, Room, serializzazione, JGit, PDFBox,
  SQLCipher e ML Kit.

La build release minificata è stata eseguita in questo ambiente. **Non è stata eseguita su un
dispositivo**: un problema di reflection introdotto dall'offuscamento non è stato escluso.

La firma è condizionale. Se sono presenti `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` e `SIGNING_KEY_PASSWORD` (o `keystore.properties`), Gradle usa quel keystore;
altrimenti usa la chiave debug. `package-release-apk.sh` distingue i due casi:

- certificato `CN=Android Debug` → file `ITAGPT-release-debug-signed-2.0.0.apk`, etichettato come
  **non ufficiale**;
- certificato reale → `ITAGPT-release-2.0.0.apk`.

Con `REQUIRE_OFFICIAL_RELEASE_SIGNATURE=1` lo script fallisce se la firma è ancora quella debug.
La pipeline imposta questa variabile solo quando `ITAGPT_KEYSTORE_BASE64` è presente nei Secrets.
Le chiavi non vanno mai committate.

## 6. Test

I test unitari JVM coprono la logica pura, senza Android framework:

- chat: sessioni, streaming, titoli, stato, export Markdown/TXT, allegati;
- prompt: multilingua, Vision, fonti, tutor, Code, contesto OCR/documenti;
- modelli: profili, quality gate, sampler, valutazione, embedding e indice semantico;
- italiano: calcolatrice, controllo meccanico, lessico del tutor;
- documenti: DOCX, XLSX, CSV, TXT, estensioni non supportate e limiti;
- Code: parsing patch, analisi HTML/Java/C++, progetto, quota e agente;
- infrastruttura: timing di generazione, UTF-8, file, sicurezza.

Totale in questa release: **222 test JVM, 0 falliti, 0 errori, 0 saltati**. La CI esegue gli stessi
quattro moduli. Non esistono test strumentali: richiederebbero un device.

## 7. CI

`.github/workflows/android.yml`:

1. JDK 17, Android SDK 35, NDK 27.2.12479018 e CMake 3.22.1;
2. `fetch-llama.sh`, `fetch-bundled-model.sh`, `fetch-bundled-chat.sh` e `build-native.sh` per
   `arm64-v8a`, `armeabi-v7a` e `x86_64`;
3. test JVM dei quattro moduli (`:core`, `:code`, `:llm`, `:app`);
4. `:app:lintDebug`;
5. preparazione opzionale del keystore dai GitHub Secrets;
6. `assembleDebug` e `assembleRelease` (R8 + resource shrinking);
7. `package-debug-apk.sh` e `package-release-apk.sh`, con verifica di ABI, modelli, firma,
   manifest e checksum;
8. `sha256sum -c` esplicito sugli APK e riepilogo nella pagina della run;
9. upload di **ITAGPT-Debug-APK**, **ITAGPT-Release-APK** (entrambi con `.sha256` e `.txt`) e
   **ITAGPT-CI-Reports** (JUnit e lint).

Il workflow è avviabile manualmente (`workflow_dispatch`) e non impone soglie di dimensione.
Gli APK sono universali: non ci sono split per ABI.

## Problemi comuni

| Sintomo | Causa / soluzione |
|---|---|
| `Could not find com.tom-roush:pdfbox-android:2.0.27.3` | La versione pubblica è `2.0.27.0`; il catalogo versioni è fissato a quella. |
| `Cannot access androidx.room.RoomDatabase` in `:code` o `:app` | `AppDatabase` è API pubblica di `:core`: il modulo consumatore deve avere `room-runtime`. Già configurato. |
| `UnsatisfiedLinkError: libbigbai.so` | La libreria non è stata costruita per quell'ABI. Rieseguire `build-native.sh` e controllare `llm/src/main/jniLibs/<abi>/`. |
| `apksigner: java: not found` | `JAVA_HOME` non impostato nella shell. Esportare il JDK 17 e aggiungere `$JAVA_HOME/bin` al `PATH`. |
| Build nativa uccisa (OOM) | Ridurre `JOBS` a 1 e chiudere altri processi. |
| Build Gradle uccisa durante il packaging | Alzare `-Xmx` in `gradle.properties`; il modello da 1,1 GB richiede più memoria del solo codice. |
| `Failed to find package 'tools'` | `android-actions/setup-android@v3` chiedeva il pacchetto `tools`, rimosso dal repository Android. Il workflow usa `v4` e dichiara i pacchetti esplicitamente. |
