#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {
using Cancellation = std::atomic_bool;
constexpr int max_prompt_bytes = 2 * 1024 * 1024;
constexpr uint64_t max_gguf_metadata_bytes = 64 * 1024 * 1024;

enum Result { ok, load_failed, context_exceeded, generation_failed };

std::string read_bytes(JNIEnv *env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}

bool aborted(void *state) {
    return static_cast<Cancellation *>(state)->load(std::memory_order_relaxed);
}

jintArray result(JNIEnv *env, Result status, int prompt = 0, int completion = 0) {
    jint values[] = {status, prompt, completion};
    jintArray array = env->NewIntArray(3);
    if (array) env->SetIntArrayRegion(array, 0, 3, values);
    return array;
}

std::string format_prompt(const llama_model *model, const std::vector<llama_chat_message> &turns) {
    const char *model_template = llama_model_chat_template(model, nullptr);
    if (!model_template) {
        char architecture[64]{};
        llama_model_meta_val_str(model, "general.architecture", architecture, sizeof(architecture));
        const std::string arch(architecture);
        if (arch.rfind("qwen", 0) == 0) model_template = "chatml";
        else if (arch.rfind("gemma", 0) == 0) model_template = "gemma";
    }
    if (model_template) {
        const int length = llama_chat_apply_template(model_template, turns.data(), turns.size(), true, nullptr, 0);
        if (length > max_prompt_bytes) return {};
        if (length > 0) {
            std::string prompt(length + 1, '\0');
            const int written = llama_chat_apply_template(model_template, turns.data(), turns.size(), true,
                prompt.data(), prompt.size());
            if (written == length) {
                prompt.resize(written);
                return prompt;
            }
        }
    }
    std::string prompt;
    for (const auto &turn : turns) {
        prompt += turn.role;
        prompt += ": ";
        prompt += turn.content;
        prompt += '\n';
    }
    return prompt + "assistant:";
}

std::vector<llama_token> prompt_tokens(const llama_model *model, std::vector<llama_chat_message> turns,
                                      int budget, const Cancellation *cancel) {
    const auto *vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(budget);
    while (!cancel->load(std::memory_order_relaxed)) {
        size_t bytes = 0;
        for (const auto &turn : turns) bytes += std::strlen(turn.content);
        if (bytes <= max_prompt_bytes / 2) {
            const auto prompt = format_prompt(model, turns);
            if (!prompt.empty()) {
                const int count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), budget, true, true);
                if (count > 0) {
                    tokens.resize(count);
                    return tokens;
                }
            }
        }
        const auto first_user = std::find_if(turns.begin(), turns.end(), [](const auto &turn) {
            return std::strcmp(turn.role, "user") == 0;
        });
        if (first_user == turns.end()) break;
        const auto next_user = std::find_if(first_user + 1, turns.end(), [](const auto &turn) {
            return std::strcmp(turn.role, "user") == 0;
        });
        if (next_user == turns.end()) break;
        turns.erase(first_user, next_user);
    }
    return {};
}

bool emit_piece(JNIEnv *env, jobject sink, jmethodID callback, const llama_vocab *vocab, llama_token token) {
    std::vector<char> piece(64);
    int32_t size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
    if (size < 0) {
        if (size < -max_prompt_bytes) return false;
        piece.resize(-size);
        size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
    }
    if (size <= 0) return size == 0;
    jbyteArray bytes = env->NewByteArray(size);
    if (!bytes) return false;
    env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(piece.data()));
    const bool keep_going = !env->ExceptionCheck() && env->CallBooleanMethod(sink, callback, bytes);
    env->DeleteLocalRef(bytes);
    return keep_going && !env->ExceptionCheck();
}
}

#define JNI_METHOD(name) Java_com_miniichatNext_carter_data_localmodel_LlamaJni_##name

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(isValidModel)(JNIEnv *env, jobject, jbyteArray path) {
    try {
        const auto filename = read_bytes(env, path);
        struct stat file{};
        if (lstat(filename.c_str(), &file) != 0 || !S_ISREG(file.st_mode)) return false;
        const auto close_file = [](FILE *stream) { std::fclose(stream); };
        const std::unique_ptr<FILE, decltype(close_file)> input(std::fopen(filename.c_str(), "rb"), close_file);
        if (!input || file.st_size <= 0) return false;
        const auto read_metadata = [](void *source, void *output, uint64_t offset, size_t len) -> size_t {
            auto *stream = static_cast<FILE *>(source);
            if (std::fseek(stream, static_cast<long>(offset), SEEK_SET) != 0) return 0;
            return std::fread(output, 1, len, stream);
        };
        const std::unique_ptr<gguf_context, decltype(&gguf_free)> gguf(
            gguf_init_from_callback(read_metadata, input.get(), 0,
                std::min<uint64_t>(file.st_size, max_gguf_metadata_bytes), {true, nullptr}), gguf_free);
        if (!gguf) return false;
        const auto architecture = gguf_find_key(gguf.get(), "general.architecture");
        if (architecture < 0 || gguf_get_kv_type(gguf.get(), architecture) != GGUF_TYPE_STRING) return false;
        const std::string arch(gguf_get_val_str(gguf.get(), architecture));
        if (arch.empty() || arch == "clip") return false;
        const auto split = gguf_find_key(gguf.get(), "split.count");
        if (split >= 0 && (gguf_get_kv_type(gguf.get(), split) != GGUF_TYPE_UINT16 ||
                          gguf_get_val_u16(gguf.get(), split) > 1)) return false;
        const auto data_offset = gguf_get_data_offset(gguf.get());
        if (file.st_size < 0 || data_offset >= static_cast<uint64_t>(file.st_size)) return false;
        const auto data_size = static_cast<uint64_t>(file.st_size) - data_offset;
        for (int64_t tensor = 0; tensor < gguf_get_n_tensors(gguf.get()); ++tensor) {
            const auto offset = gguf_get_tensor_offset(gguf.get(), tensor);
            if (offset > data_size || gguf_get_tensor_size(gguf.get(), tensor) > data_size - offset) return false;
        }
        return true;
    } catch (const std::exception &) {
        return false;
    }
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(newCancellation)(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) Cancellation(false));
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(cancel)(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Cancellation *>(handle)->store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(freeCancellation)(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Cancellation *>(handle);
}

extern "C" JNIEXPORT jintArray JNICALL JNI_METHOD(generate)(
    JNIEnv *env, jobject, jlong handle, jbyteArray path, jobjectArray roles, jobjectArray messages,
    jint context, jint threads, jint max_tokens, jfloat temperature, jfloat top_p, jint top_k,
    jfloat repeat_penalty, jint seed, jobject sink) {
    try {
        if (!handle) return result(env, load_failed);
        auto *cancel = reinterpret_cast<Cancellation *>(handle);
        if (aborted(cancel)) return result(env, generation_failed);
        static std::once_flag initialized;
        std::call_once(initialized, llama_backend_init);
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.progress_callback_user_data = cancel;
        model_params.progress_callback = [](float, void *state) { return !aborted(state); };
        const auto filename = read_bytes(env, path);
        const std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(filename.c_str(), model_params), llama_model_free);
        if (!model || llama_model_has_encoder(model.get()) || !llama_model_has_decoder(model.get())) {
            return result(env, load_failed);
        }
        const int train_context = llama_model_n_ctx_train(model.get());
        if (train_context > 0) context = std::min(context, train_context);
        if (context < 32) return result(env, context_exceeded);
        max_tokens = std::min(max_tokens, context / 2);

        const auto count = env->GetArrayLength(messages);
        if (env->GetArrayLength(roles) != count) return result(env, generation_failed);
        std::vector<std::string> role_strings(count), message_strings(count);
        std::vector<llama_chat_message> turns;
        for (jsize i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            auto text = static_cast<jbyteArray>(env->GetObjectArrayElement(messages, i));
            const char *chars = env->GetStringUTFChars(role, nullptr);
            if (!chars) return nullptr;
            role_strings[i] = chars;
            env->ReleaseStringUTFChars(role, chars);
            message_strings[i] = read_bytes(env, text);
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(text);
            turns.push_back({role_strings[i].c_str(), message_strings[i].c_str()});
        }
        auto tokens = prompt_tokens(model.get(), std::move(turns), context - max_tokens, cancel);
        if (tokens.empty()) return result(env, context_exceeded);

        auto context_params = llama_context_default_params();
        context_params.n_ctx = context;
        context_params.n_batch = std::min(256, context);
        context_params.n_ubatch = context_params.n_batch;
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        context_params.abort_callback = aborted;
        context_params.abort_callback_data = cancel;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        const std::unique_ptr<llama_context, decltype(&llama_free)> ctx(
            llama_init_from_model(model.get(), context_params), llama_free);
        if (!ctx) return result(env, load_failed);
        if (tokens.size() + max_tokens > llama_n_ctx(ctx.get())) return result(env, context_exceeded);

        const auto *vocab = llama_model_get_vocab(model.get());
        const std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!sampler) return result(env, load_failed);
        auto *chain = sampler.get();
        if (repeat_penalty != 1.0f) llama_sampler_chain_add(chain,
            llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
        if (temperature == 0.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        } else {
            if (top_k > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
            llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
            if (top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(chain, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
        }
        for (auto token : tokens) llama_sampler_accept(chain, token);
        for (size_t offset = 0; offset < tokens.size(); offset += context_params.n_batch) {
            const size_t batch = std::min<size_t>(context_params.n_batch, tokens.size() - offset);
            if (aborted(cancel) || llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + offset, batch)) != 0) {
                return result(env, generation_failed);
            }
        }
        const jclass sink_class = env->GetObjectClass(sink);
        const jmethodID callback = env->GetMethodID(sink_class, "onToken", "([B)Z");
        env->DeleteLocalRef(sink_class);
        if (!callback) return nullptr;
        int completion = 0;
        while (completion < max_tokens && !aborted(cancel)) {
            auto token = llama_sampler_sample(chain, ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            if (!emit_piece(env, sink, callback, vocab, token)) {
                if (env->ExceptionCheck()) return nullptr;
                return result(env, generation_failed);
            }
            ++completion;
            if (completion < max_tokens && !aborted(cancel) &&
                llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) {
                return result(env, generation_failed);
            }
        }
        return result(env, ok, tokens.size(), completion);
    } catch (const std::exception &) {
        return result(env, generation_failed);
    }
}
