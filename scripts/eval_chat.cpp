// Minimal host generator for the Italian quality evaluation.
// Reads a ready-made ChatML prompt from a file and prints the answer.
// Usage: eval_chat model.gguf prompt.txt [n_predict] [temperature]
#include "llama.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

int main(int argc, char ** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s model.gguf prompt.txt [n] [temp]\n", argv[0]);
        return 2;
    }
    const int n_predict = argc > 3 ? std::atoi(argv[3]) : 220;
    const float temperature = argc > 4 ? std::atof(argv[4]) : 0.7f;

    std::ifstream input(argv[2]);
    std::stringstream buffer;
    buffer << input.rdbuf();
    const std::string prompt = buffer.str();
    if (prompt.empty()) {
        std::fprintf(stderr, "empty prompt\n");
        return 2;
    }

    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(argv[1], mp);
    if (model == nullptr) {
        std::fprintf(stderr, "load failed\n");
        return 1;
    }
    const llama_vocab * vocab = llama_model_get_vocab(model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 2048;
    cp.n_ubatch = 512;
    cp.n_threads = 6;
    cp.n_threads_batch = 6;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        std::fprintf(stderr, "context failed\n");
        llama_model_free(model);
        return 1;
    }

    int count = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    if (count < 0) count = -count;
    std::vector<llama_token> tokens(count);
    count = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), tokens.data(), count, true, true);
    if (count < 0 || count >= 2000) {
        std::fprintf(stderr, "prompt too long\n");
        return 1;
    }
    tokens.resize(count);

    if (llama_decode(ctx, llama_batch_get_one(tokens.data(), count)) != 0) {
        std::fprintf(stderr, "prompt decode failed\n");
        return 1;
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(1234));

    llama_batch batch = llama_batch_init(1, 0, 1);
    int position = count;
    int generated = 0;
    bool eog = false;
    char piece[256];
    for (int i = 0; i < n_predict; i++) {
        const llama_token id = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, id);
        if (llama_vocab_is_eog(vocab, id)) {
            eog = true;
            break;
        }
        const int length = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (length > 0) std::fwrite(piece, 1, length, stdout);

        batch.token[0] = id;
        batch.pos[0] = position++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;
        if (llama_decode(ctx, batch) != 0) {
            std::fprintf(stderr, "\ndecode failed at token %d\n", i);
            break;
        }
        generated++;
    }
    std::printf("\n[[tokens=%d finish=%s]]\n", generated, eog ? "EOS" : "LENGTH");

    llama_batch_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
