// BIG B AI — JNI bridge to llama.cpp (text + multimodal inference).
//
// Design notes:
//  * One model is loaded at a time; the Kotlin layer enforces this and the
//    native session owns exactly one llama_model/llama_context pair.
//  * Token generation is pull-based (one token per JNI call). This keeps
//    cancellation responsive, lets the UI stream text, and avoids calling back
//    into the JVM from a native worker thread.
//  * Every reported timing comes from llama.cpp's own perf counters or from the
//    wall clock in Kotlin; nothing is synthesised.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "BigBAINative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;
std::mutex g_error_mutex;
std::string g_last_error;

// Live session registry: nativeCancel() can be called from the UI thread while
// the inference thread owns the session, so cancellation must never dereference
// a handle that has already been freed.
std::mutex g_sessions_mutex;
std::set<void *> g_sessions;

void set_error(const std::string & message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
    LOGE("%s", message.c_str());
}

void llama_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_error = text;
    }
}

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    mtmd_context * mctx = nullptr;

    std::atomic<bool> cancel{false};
    // 0 unknown, 1 EOS/EOG, 2 token limit, 3 cancelled. Read by Kotlin after the
    // loop so the app can distinguish a finished answer from a cut one.
    std::atomic<int32_t> finish_reason{0};
    int32_t n_batch = 256;
    int32_t n_past = 0;
    int32_t generated = 0;
    int32_t max_tokens = 0;
    // Capability of the loaded projector, set once at load. It must never be
    // reset by a text-only generation: doing that made vision work only until
    // the first text message and then fail with "No multimodal projector".
    bool has_vision = false;
};

Session * from_handle(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

// A second, independent session for the bundled embedding model. It has its own
// model and context so retrieval never evicts the chat model and a failure in
// one cannot corrupt the other.
struct Embedder {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t dim = 0;
    int32_t n_ctx = 512;
};

Embedder * embedder_from_handle(jlong handle) {
    return reinterpret_cast<Embedder *>(handle);
}

bool ensure_backend() {
    std::call_once(g_backend_once, []() {
        llama_log_set(llama_log_callback, nullptr);
        llama_backend_init();
    });
    return true;
}

void free_sampler(Session * session) {
    if (session->sampler != nullptr) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }
}

bool create_sampler(Session * session, float temperature, float top_p, int32_t top_k,
                    float repeat_penalty, float min_p, int32_t seed) {
    free_sampler(session);
    auto params = llama_sampler_chain_default_params();
    params.no_perf = false;
    llama_sampler * chain = llama_sampler_chain_init(params);
    if (chain == nullptr) return false;
    // Repetition control first: small quantized models otherwise fall into a
    // loop that repeats the same sentence until max_tokens is reached. The
    // strength is a per-model parameter (1.0 disables it); the last 64 tokens
    // are penalised so normal prose is unaffected.
    const float penalty = std::min(1.6f, std::max(1.0f, repeat_penalty));
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
        static_cast<int32_t>(llama_vocab_n_tokens(session->vocab)),
        64, penalty, 0.0f, 0.0f));
    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }
    // min-p removes the long tail of low-probability tokens that makes small
    // models invent words, while keeping the nucleus intact at low entropy.
    if (min_p > 0.0f && min_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        const uint32_t chosen_seed = seed >= 0 ? static_cast<uint32_t>(seed) : LLAMA_DEFAULT_SEED;
        llama_sampler_chain_add(chain, llama_sampler_init_dist(chosen_seed));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }
    session->sampler = chain;
    return true;
}

bool decode_tokens(Session * session, const llama_token * tokens, int32_t count) {
    for (int32_t offset = 0; offset < count; offset += session->n_batch) {
        const int32_t chunk = std::min(session->n_batch, count - offset);
        llama_batch batch = llama_batch_get_one(
            const_cast<llama_token *>(tokens + offset), chunk);
        const int32_t rc = llama_decode(session->ctx, batch);
        if (rc != 0) {
            set_error("llama_decode failed with code " + std::to_string(rc));
            return false;
        }
        if (session->cancel.load()) return false;
    }
    return true;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text,
                                  bool add_special) {
    int32_t needed = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                    nullptr, 0, add_special, true);
    if (needed < 0) needed = -needed;
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                           tokens.data(), needed, add_special, true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jbyteArray string_to_jbytes(JNIEnv * env, const char * data, size_t length) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(length));
    if (array != nullptr && length > 0) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte *>(data));
    }
    return array;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_bigbai_llm_NativeLlama_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF("llama.cpp v1.0.0 (b1b770d)");
}

JNIEXPORT jstring JNICALL
Java_com_bigbai_llm_NativeLlama_nativeLastError(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_bigbai_llm_NativeLlama_nativeCapabilities(JNIEnv * env, jclass) {
    std::string caps = std::string("gpu_offload=") +
        (llama_supports_gpu_offload() ? "true" : "false") +
        ";mmap=" + (llama_supports_mmap() ? "true" : "false") +
        ";mlock=" + (llama_supports_mlock() ? "true" : "false") +
        ";backend=cpu";
    return env->NewStringUTF(caps.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_bigbai_llm_NativeLlama_nativeLoadModel(
    JNIEnv * env, jclass, jstring jpath, jstring jmmproj, jint n_ctx, jint n_threads,
    jint n_batch, jboolean use_mlock, jboolean use_mmap) {
    ensure_backend();
    const std::string path = jstring_to_string(env, jpath);
    const std::string mmproj = jstring_to_string(env, jmmproj);
    if (path.empty()) {
        set_error("Empty model path");
        return 0;
    }

    auto * session = new Session();
    session->n_batch = std::max<int32_t>(1, n_batch);
    session->cancel.store(false);

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    // v1.0.0 replaced the mmap/mlock booleans with a load-mode enum. AUTO lets
    // llama.cpp mmap the weights when the platform supports it, which is what
    // Android wants; forcing mlock can provoke the low-memory killer.
    (void) use_mlock;
    (void) use_mmap;
    model_params.load_mode = LLAMA_LOAD_MODE_AUTO;

    session->model = llama_model_load_from_file(path.c_str(), model_params);
    if (session->model == nullptr) {
        set_error("Unable to load GGUF model: " + path);
        delete session;
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(std::max<int32_t>(512, n_ctx));
    ctx_params.n_batch = static_cast<uint32_t>(session->n_batch);
    ctx_params.n_ubatch = static_cast<uint32_t>(std::min<int32_t>(session->n_batch, 512));
    ctx_params.n_threads = std::max<int32_t>(1, n_threads);
    ctx_params.n_threads_batch = std::max<int32_t>(1, n_threads);
    ctx_params.no_perf = false;
    ctx_params.abort_callback = [](void * data) -> bool {
        return static_cast<Session *>(data)->cancel.load();
    };
    ctx_params.abort_callback_data = session;

    session->ctx = llama_init_from_model(session->model, ctx_params);
    if (session->ctx == nullptr) {
        set_error("Unable to create llama context (out of memory?)");
        llama_model_free(session->model);
        delete session;
        return 0;
    }

    if (!mmproj.empty()) {
        auto mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = false;
        mtmd_params.print_timings = false;
        mtmd_params.n_threads = std::max<int32_t>(1, n_threads);
        session->mctx = mtmd_init_from_file(mmproj.c_str(), session->model, mtmd_params);
        if (session->mctx == nullptr) {
            set_error("Unable to load multimodal projector: " + mmproj);
            llama_free(session->ctx);
            llama_model_free(session->model);
            delete session;
            return 0;
        }
        session->has_vision = mtmd_support_vision(session->mctx);
    }

    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        g_sessions.insert(session);
    }

    LOGI("model loaded: ctx=%d threads=%d batch=%d vision=%d",
         n_ctx, n_threads, session->n_batch, session->has_vision ? 1 : 0);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_bigbai_llm_NativeLlama_nativeFreeModel(JNIEnv *, jclass, jlong handle) {
    Session * session = from_handle(handle);
    if (session == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        g_sessions.erase(session);
    }
    free_sampler(session);
    if (session->mctx != nullptr) mtmd_free(session->mctx);
    if (session->ctx != nullptr) llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}

JNIEXPORT jstring JNICALL
Java_com_bigbai_llm_NativeLlama_nativeModelInfo(JNIEnv * env, jclass, jlong handle) {
    Session * session = from_handle(handle);
    if (session == nullptr) return env->NewStringUTF("");
    char desc[512] = {0};
    llama_model_desc(session->model, desc, sizeof(desc));
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    const int32_t n_ctx_train = llama_model_n_ctx_train(session->model);
    const int32_t n_layer = llama_model_n_layer(session->model);
    const int32_t n_head = llama_model_n_head(session->model);
    const int32_t n_head_kv = llama_model_n_head_kv(session->model);
    const int32_t n_embd = llama_model_n_embd(session->model);
    const uint64_t n_params = llama_model_n_params(session->model);
    const uint64_t size = llama_model_size(session->model);
    const int32_t head_dim = n_head > 0 ? n_embd / n_head : 0;

    std::string info;
    info += "desc=" + std::string(desc) + "\n";
    info += "n_ctx=" + std::to_string(n_ctx) + "\n";
    info += "n_ctx_train=" + std::to_string(n_ctx_train) + "\n";
    info += "n_layer=" + std::to_string(n_layer) + "\n";
    info += "n_head=" + std::to_string(n_head) + "\n";
    info += "n_head_kv=" + std::to_string(n_head_kv) + "\n";
    info += "n_embd=" + std::to_string(n_embd) + "\n";
    info += "head_dim=" + std::to_string(head_dim) + "\n";
    info += "n_params=" + std::to_string(n_params) + "\n";
    info += "size=" + std::to_string(size) + "\n";
    info += "vision=" + std::string(session->has_vision ? "true" : "false") + "\n";
    info += "n_vocab=" + std::to_string(llama_vocab_n_tokens(session->vocab)) + "\n";
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_bigbai_llm_NativeLlama_nativeApplyChatTemplate(
    JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents,
    jboolean add_assistant) {
    Session * session = from_handle(handle);
    if (session == nullptr) return nullptr;

    const jsize count = env->GetArrayLength(roles);
    std::vector<std::string> role_storage(static_cast<size_t>(count));
    std::vector<std::string> content_storage(static_cast<size_t>(count));
    std::vector<llama_chat_message> messages(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_storage[i] = jstring_to_string(env, role);
        content_storage[i] = jstring_to_string(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        messages[i].role = role_storage[i].c_str();
        messages[i].content = content_storage[i].c_str();
    }

    const char * tmpl = llama_model_chat_template(session->model, nullptr);
    int32_t needed = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                               add_assistant, nullptr, 0);
    if (needed <= 0) {
        set_error("Chat template not supported by this model");
        return nullptr;
    }
    std::string output(static_cast<size_t>(needed), '\0');
    const int32_t written = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                                      add_assistant, output.data(), needed);
    if (written <= 0) {
        set_error("Chat template application failed");
        return nullptr;
    }
    output.resize(static_cast<size_t>(written));
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT jint JNICALL
Java_com_bigbai_llm_NativeLlama_nativeCountTokens(JNIEnv * env, jclass, jlong handle, jstring text) {
    Session * session = from_handle(handle);
    if (session == nullptr) return 0;
    const std::string value = jstring_to_string(env, text);
    const auto tokens = tokenize(session->vocab, value, true);
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jboolean JNICALL
Java_com_bigbai_llm_NativeLlama_nativeBegin(
    JNIEnv * env, jclass, jlong handle, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty,
    jfloat min_p, jint seed, jboolean add_special) {
    Session * session = from_handle(handle);
    if (session == nullptr) return JNI_FALSE;

    session->cancel.store(false);
    session->finish_reason.store(0);
    session->generated = 0;
    session->max_tokens = std::max<int32_t>(1, max_tokens);

    const std::string text = jstring_to_string(env, prompt);
    // The chat template already contains the BOS token when the model declares
    // one; tokenizing with add_special=true on top of that produced a double BOS
    // and degraded the first tokens of every answer.
    const auto tokens = tokenize(session->vocab, text, add_special == JNI_TRUE);
    if (tokens.empty()) {
        set_error("Prompt tokenization produced no tokens");
        return JNI_FALSE;
    }

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (static_cast<int32_t>(tokens.size()) + 8 >= n_ctx) {
        set_error("Prompt is larger than the model context window");
        return JNI_FALSE;
    }

    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->n_past = 0;

    if (!decode_tokens(session, tokens.data(), static_cast<int32_t>(tokens.size()))) {
        if (!session->cancel.load()) set_error("Prompt evaluation failed");
        return JNI_FALSE;
    }
    session->n_past = static_cast<int32_t>(tokens.size());
    if (!create_sampler(session, temperature, top_p, top_k, repeat_penalty, min_p, seed)) {
        set_error("Unable to create sampler chain");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_bigbai_llm_NativeLlama_nativeBeginVision(
    JNIEnv * env, jclass, jlong handle, jstring prompt, jbyteArray rgb, jint width, jint height,
    jint max_tokens, jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty,
    jfloat min_p, jint seed, jboolean add_special) {
    Session * session = from_handle(handle);
    if (session == nullptr) return JNI_FALSE;
    if (session->mctx == nullptr || !session->has_vision) {
        set_error("No multimodal projector loaded");
        return JNI_FALSE;
    }

    session->cancel.store(false);
    session->finish_reason.store(0);
    session->generated = 0;
    session->max_tokens = std::max<int32_t>(1, max_tokens);

    const jsize data_size = env->GetArrayLength(rgb);
    if (data_size != width * height * 3) {
        set_error("Image buffer size does not match RGB dimensions");
        return JNI_FALSE;
    }
    std::vector<unsigned char> pixels(static_cast<size_t>(data_size));
    env->GetByteArrayRegion(rgb, 0, data_size, reinterpret_cast<jbyte *>(pixels.data()));

    mtmd_bitmap * bitmap = mtmd_bitmap_init(static_cast<uint32_t>(width),
                                            static_cast<uint32_t>(height), pixels.data());
    if (bitmap == nullptr) {
        set_error("Unable to allocate image bitmap");
        return JNI_FALSE;
    }

    const std::string text = std::string(mtmd_default_marker()) + "\n" +
        jstring_to_string(env, prompt);

    mtmd_input_text input{};
    input.text = text.c_str();
    input.add_special = add_special == JNI_TRUE;
    input.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = {bitmap};
    const int32_t tokenize_rc = mtmd_tokenize(session->mctx, chunks, &input, bitmaps, 1);
    if (tokenize_rc != 0) {
        set_error("Multimodal tokenization failed (code " + std::to_string(tokenize_rc) + ")");
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return JNI_FALSE;
    }

    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->n_past = 0;
    llama_pos new_n_past = 0;
    const int32_t eval_rc = mtmd_helper_eval_chunks(
        session->mctx, session->ctx, chunks, 0, 0, session->n_batch, true, &new_n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    if (eval_rc != 0) {
        set_error("Multimodal prompt evaluation failed (code " + std::to_string(eval_rc) + ")");
        return JNI_FALSE;
    }
    session->n_past = new_n_past;
    if (!create_sampler(session, temperature, top_p, top_k, repeat_penalty, min_p, seed)) {
        set_error("Unable to create sampler chain");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_bigbai_llm_NativeLlama_nativeNextToken(JNIEnv * env, jclass, jlong handle) {
    Session * session = from_handle(handle);
    if (session == nullptr || session->sampler == nullptr) return nullptr;
    if (session->cancel.load()) {
        session->finish_reason.store(3);
        return nullptr;
    }
    if (session->generated >= session->max_tokens) {
        session->finish_reason.store(2);
        return nullptr;
    }

    const llama_token id = llama_sampler_sample(session->sampler, session->ctx, -1);
    if (id < 0) return nullptr;
    if (llama_vocab_is_eog(session->vocab, id)) {
        session->finish_reason.store(1);
        return nullptr;
    }

    llama_sampler_accept(session->sampler, id);

    char buffer[512];
    int32_t length = llama_token_to_piece(session->vocab, id, buffer, sizeof(buffer), 0, false);
    if (length < 0) {
        std::vector<char> larger(static_cast<size_t>(-length) + 1);
        length = llama_token_to_piece(session->vocab, id, larger.data(),
                                      static_cast<int32_t>(larger.size()), 0, false);
        if (length < 0) return nullptr;
        jbyteArray result = string_to_jbytes(env, larger.data(), static_cast<size_t>(length));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        if (llama_decode(session->ctx, batch) != 0) return result;
        session->n_past += 1;
        session->generated += 1;
        return result;
    }

    jbyteArray result = string_to_jbytes(env, buffer, static_cast<size_t>(length));

    llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
    if (llama_decode(session->ctx, batch) != 0) {
        set_error("Token decode failed");
        return result;
    }
    session->n_past += 1;
    session->generated += 1;
    return result;
}

JNIEXPORT jint JNICALL
Java_com_bigbai_llm_NativeLlama_nativeFinishReason(JNIEnv *, jclass, jlong handle) {
    Session * session = from_handle(handle);
    if (session == nullptr) return 0;
    return static_cast<jint>(session->finish_reason.load());
}

JNIEXPORT void JNICALL
Java_com_bigbai_llm_NativeLlama_nativeCancel(JNIEnv *, jclass, jlong handle) {
    Session * session = from_handle(handle);
    if (session == nullptr) return;
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    if (g_sessions.find(session) == g_sessions.end()) return;
    session->cancel.store(true);
}

JNIEXPORT jlongArray JNICALL
Java_com_bigbai_llm_NativeLlama_nativePerf(JNIEnv * env, jclass, jlong handle) {
    Session * session = from_handle(handle);
    if (session == nullptr) return nullptr;
    const auto perf = llama_perf_context(session->ctx);
    jlong values[4] = {
        static_cast<jlong>(perf.t_p_eval_ms),
        static_cast<jlong>(perf.t_eval_ms),
        static_cast<jlong>(perf.n_p_eval),
        static_cast<jlong>(perf.n_eval),
    };
    jlongArray array = env->NewLongArray(4);
    if (array != nullptr) env->SetLongArrayRegion(array, 0, 4, values);
    return array;
}

JNIEXPORT jint JNICALL
Java_com_bigbai_llm_NativeLlama_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    Session * session = from_handle(handle);
    if (session == nullptr) return 0;
    return static_cast<jint>(llama_n_ctx(session->ctx));
}

// ---------------------------------------------------------------------------
// Embedding session (bundled multilingual-e5-small).
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_bigbai_llm_NativeLlama_nativeLoadEmbedder(
    JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    ensure_backend();
    const std::string path = jstring_to_string(env, jpath);
    if (path.empty()) {
        set_error("Empty embedding model path");
        return 0;
    }
    auto * embedder = new Embedder();
    embedder->n_ctx = std::max<int32_t>(512, n_ctx);

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_AUTO;
    embedder->model = llama_model_load_from_file(path.c_str(), model_params);
    if (embedder->model == nullptr) {
        set_error("Unable to load embedding GGUF: " + path);
        delete embedder;
        return 0;
    }
    embedder->vocab = llama_model_get_vocab(embedder->model);

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(embedder->n_ctx);
    ctx_params.n_batch = static_cast<uint32_t>(embedder->n_ctx);
    ctx_params.n_ubatch = static_cast<uint32_t>(embedder->n_ctx);
    ctx_params.n_threads = std::max<int32_t>(1, n_threads);
    ctx_params.n_threads_batch = std::max<int32_t>(1, n_threads);
    ctx_params.embeddings = true;
    // Mean pooling is what E5 was trained with. CLS would produce worse
    // retrieval, so the pooling type is part of the model contract.
    ctx_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    ctx_params.no_perf = true;

    embedder->ctx = llama_init_from_model(embedder->model, ctx_params);
    if (embedder->ctx == nullptr) {
        set_error("Unable to create embedding context (out of memory?)");
        llama_model_free(embedder->model);
        delete embedder;
        return 0;
    }
    embedder->dim = llama_model_n_embd(embedder->model);
    if (embedder->dim <= 0) {
        set_error("Embedding model reports an invalid dimension");
        llama_free(embedder->ctx);
        llama_model_free(embedder->model);
        delete embedder;
        return 0;
    }
    LOGI("embedder loaded: dim=%d ctx=%d threads=%d", embedder->dim, embedder->n_ctx, n_threads);
    return reinterpret_cast<jlong>(embedder);
}

JNIEXPORT jint JNICALL
Java_com_bigbai_llm_NativeLlama_nativeEmbedderDim(JNIEnv *, jclass, jlong handle) {
    Embedder * embedder = embedder_from_handle(handle);
    return embedder == nullptr ? 0 : static_cast<jint>(embedder->dim);
}

JNIEXPORT jfloatArray JNICALL
Java_com_bigbai_llm_NativeLlama_nativeEmbed(JNIEnv * env, jclass, jlong handle, jstring jtext) {
    Embedder * embedder = embedder_from_handle(handle);
    if (embedder == nullptr || embedder->ctx == nullptr) return nullptr;

    const std::string text = jstring_to_string(env, jtext);
    auto tokens = tokenize(embedder->vocab, text, true);
    if (tokens.empty()) {
        set_error("Embedding tokenization produced no tokens");
        return nullptr;
    }
    if (static_cast<int32_t>(tokens.size()) > embedder->n_ctx) {
        // Truncation is preferable to failing a long passage: the caller already
        // splits documents into ~700-character chunks, so this only guards
        // against pathological tokenization.
        tokens.resize(static_cast<size_t>(embedder->n_ctx));
    }

    llama_memory_clear(llama_get_memory(embedder->ctx), true);
    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    if (batch.token == nullptr) {
        set_error("Unable to allocate embedding batch");
        return nullptr;
    }
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        // Pooled embeddings are computed from the tokens marked as output.
        batch.logits[i] = 1;
    }
    batch.n_tokens = static_cast<int32_t>(tokens.size());

    // BERT-style embedding models in llama.cpp expose a decoder-only graph
    // (no KV cache use for a non-causal model); encoder-decoder models need
    // llama_encode. The branch keeps both working.
    const int32_t rc = llama_model_has_encoder(embedder->model)
        ? llama_encode(embedder->ctx, batch)
        : llama_decode(embedder->ctx, batch);
    llama_batch_free(batch);
    if (rc < 0) {
        set_error("Embedding evaluation failed with code " + std::to_string(rc));
        return nullptr;
    }

    const float * values = llama_get_embeddings_seq(embedder->ctx, 0);
    if (values == nullptr) values = llama_get_embeddings(embedder->ctx);
    if (values == nullptr) {
        set_error("The model did not return an embedding");
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(embedder->dim);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, embedder->dim, values);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_bigbai_llm_NativeLlama_nativeFreeEmbedder(JNIEnv *, jclass, jlong handle) {
    Embedder * embedder = embedder_from_handle(handle);
    if (embedder == nullptr) return;
    if (embedder->ctx != nullptr) llama_free(embedder->ctx);
    if (embedder->model != nullptr) llama_model_free(embedder->model);
    delete embedder;
}

} // extern "C"
