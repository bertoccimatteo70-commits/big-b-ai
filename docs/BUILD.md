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

Il Gradle wrapper JAR non è committato: la CI usa `gradle/actions/setup-gradle` con
`gradle-version: 8.11.1`. In locale serve un Gradle 8.11.1 nel `PATH`.

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
gradle --no-daemon :core:testDebugUnitTest :code:testDebugUnitTest
gradle --no-daemon assembleDebug
gradle --no-daemon assembleRelease
bash scripts/package-apk.sh
```

`gradle.properties` è tarato su host con 2 core e ~4 GB di RAM:

```properties
org.gradle.jvmargs=-Xmx2816m -XX:MaxMetaspaceSize=768m
org.gradle.workers.max=2
kotlin.compiler.execution.strategy=in-process
```

Su una macchina più grande si possono alzare questi valori.

## 3. Firma

La release usa, in ordine:

1. variabili d'ambiente `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
   `SIGNING_KEY_PASSWORD`;
2. `keystore.properties` nella root (non committato);
3. **fallback alla chiave debug**, con un APK comunque installabile e verificabile.

Per una release firmata:

```properties
# keystore.properties
storeFile=/percorso/release.jks
storePassword=...
keyAlias=bigbai
keyPassword=...
```

L'APK di questa release è firmato con la chiave debug (`CN=Android Debug`): è dichiarato nel
README e nel file `dist/BIG-B-AI-1.0.0.txt`.

## 4. Test

I test unitari JVM coprono la logica pura, senza Android framework:

- `PatchEngineTest` (8 test): parsing del protocollo patch, path, blocchi non chiusi, diff e statistiche.
- `StaticAnalyzersTest` (10 test): HTML, Java, C++, CSS.
- `HtmlTextTest` (4 test): rimozione script/style, entità, spazi.

Totale: **18 test, tutti superati**. Non esistono test strumentali: richiederebbero un device.

## 5. CI

`.github/workflows/android.yml`:

1. JDK 17 + Gradle 8.11.1 + Android SDK + NDK/CMake;
2. `fetch-llama.sh` e `build-native.sh`;
3. test JVM;
4. `assembleDebug` e `assembleRelease`;
5. `package-apk.sh` e upload degli artifact (`dist/*.apk`, checksum, metadata).

## Problemi comuni

| Sintomo | Causa / soluzione |
|---|---|
| `Could not find com.tom-roush:pdfbox-android:2.0.27.3` | La versione pubblica è `2.0.27.0`; il catalogo versioni è fissato a quella. |
| `Cannot access androidx.room.RoomDatabase` in `:code` o `:app` | `AppDatabase` è API pubblica di `:core`: il modulo consumatore deve avere `room-runtime`. Già configurato. |
| `UnsatisfiedLinkError: libbigbai.so` | La libreria non è stata costruita o l'ABI non è `arm64-v8a`. Rieseguire `build-native.sh`. |
| `apksigner: java: not found` | `JAVA_HOME` non impostato nella shell. Esportare il JDK 17. |
| Build nativa uccisa (OOM) | Ridurre `JOBS` a 1 e chiudere altri processi. |
