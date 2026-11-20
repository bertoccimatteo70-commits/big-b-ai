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
bash scripts/fetch-llama.sh     # clona llama.cpp al commit fissato in third_party/
bash scripts/build-native.sh    # compila e strippa libbigbai.so
```

`build-native.sh` usa CMake + NDK, ABI `arm64-v8a`, `-DGGML_VULKAN=OFF`, `-DGGML_OPENMP=OFF`,
`-DLLAMA_CURL=OFF`, e produce:

```
llm/src/main/jniLibs/arm64-v8a/libbigbai.so   (~5,7 MB stripped)
```

La build richiede circa 3,5 GB di RAM con `JOBS=2`. Su macchine piccole:

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
./gradlew --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest
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

Lo script copia l'APK in `dist/BIG-B-AI-debug-<versione>.apk`, verifica che
`lib/arm64-v8a/libbigbai.so` sia davvero dentro l'APK (un APK senza motore nativo non deve mai
essere pubblicato), calcola lo SHA-256 e scrive `dist/BIG-B-AI-debug-<versione>.txt` con
dimensione, data, package e firma.

La variante debug usa `applicationId` `com.bigbai.app.debug` e `versionName` `1.0.0-debug`, quindi
convive con una eventuale build di release sullo stesso dispositivo.

`app/build.gradle.kts` supporta ancora la firma di release (`keystore.properties` o variabili
`SIGNING_*`) per build future, ma non fa parte di questa consegna.

## 4. Test

I test unitari JVM coprono la logica pura, senza Android framework:

- `PatchEngineTest` (8 test): parsing del protocollo patch, path, blocchi non chiusi, diff e statistiche.
- `StaticAnalyzersTest` (10 test): HTML, Java, C++, CSS.
- `HtmlTextTest` (4 test): rimozione script/style, entità, spazi.

Totale: **22 test, tutti superati**. Non esistono test strumentali: richiederebbero un device.

## 5. CI

`.github/workflows/android.yml` (solo build debug):

1. JDK 17, Android SDK con `android-actions/setup-android@v4` (niente pacchetto `tools`, rimosso da
   Google), NDK 27.2.12479018 e CMake 3.22.1;
2. `fetch-llama.sh` e `build-native.sh`;
3. test JVM (`:core`, `:code`);
4. `./gradlew assembleDebug`;
5. `package-debug-apk.sh` e upload dell'artifact **BIG-B-AI-Debug-APK** (APK + `.sha256`,
   retention 30 giorni).

## Problemi comuni

| Sintomo | Causa / soluzione |
|---|---|
| `Could not find com.tom-roush:pdfbox-android:2.0.27.3` | La versione pubblica è `2.0.27.0`; il catalogo versioni è fissato a quella. |
| `Cannot access androidx.room.RoomDatabase` in `:code` o `:app` | `AppDatabase` è API pubblica di `:core`: il modulo consumatore deve avere `room-runtime`. Già configurato. |
| `UnsatisfiedLinkError: libbigbai.so` | La libreria non è stata costruita o l'ABI non è `arm64-v8a`. Rieseguire `build-native.sh`. |
| `apksigner: java: not found` | `JAVA_HOME` non impostato nella shell. Esportare il JDK 17. |
| Build nativa uccisa (OOM) | Ridurre `JOBS` a 1 e chiudere altri processi. |
| `Failed to find package 'tools'` | `android-actions/setup-android@v3` chiedeva il pacchetto `tools`, rimosso dal repository Android. Il workflow usa `v4` e dichiara i pacchetti esplicitamente. |
