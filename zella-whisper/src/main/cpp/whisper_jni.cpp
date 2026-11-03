// JNI bridge to whisper.cpp for Zella. One Engine per loaded model. Transcribe returns one entry per
// word (start ms, end ms, text, probability), using whisper.cpp's word split (max_len 1).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "ZellaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {
struct Engine {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancelled{false};
};

struct Progress {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
};

void progress_cb(whisper_context *, whisper_state *, int progress, void *user) {
    auto *p = static_cast<Progress *>(user);
    if (p->listener) p->env->CallVoidMethod(p->listener, p->onProgress, progress);
}

bool abort_cb(void *user) { return static_cast<Engine *>(user)->cancelled.load(); }
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_zellahq_whisper_WhisperNative_init(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) return 0;
    auto *e = new Engine();
    e->ctx = ctx;
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zellahq_whisper_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    whisper_free(e->ctx);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zellahq_whisper_WhisperNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e) e->cancelled.store(true);
}

/**
 * pcm: 16 kHz mono float. language: a whisper code ("en", "bn") or "auto". Returns
 * [lang, w0text, w0start, w0end, w0prob, w1text, ...] as strings, or null when cancelled or failed.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_zellahq_whisper_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm,
                                                   jstring language, jboolean translate, jint threads,
                                                   jobject listener) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;
    e->cancelled.store(false);
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string langStr(lang);
    env->ReleaseStringUTFChars(language, lang);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.translate = translate;
    wp.language = langStr.c_str();
    wp.detect_language = false;
    wp.no_context = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    // One segment per word, with word times: what captions and silence cutting need.
    wp.token_timestamps = true;
    wp.max_len = 1;
    wp.split_on_word = true;
    wp.suppress_blank = true;

    Progress prog{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        prog.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        wp.progress_callback = progress_cb;
        wp.progress_callback_user_data = &prog;
    }
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = e;

    if (whisper_full(e->ctx, wp, samples.data(), (int) samples.size()) != 0 || e->cancelled.load()) {
        return nullptr;
    }
    const int segs = whisper_full_n_segments(e->ctx);
    std::vector<std::string> out;
    out.emplace_back(whisper_lang_str(whisper_full_lang_id(e->ctx)));
    for (int i = 0; i < segs; i++) {
        std::string text = whisper_full_get_segment_text(e->ctx, i);
        if (text.empty()) continue;
        // Average token probability: low values mark guesses (and help spot fillers later).
        const int nt = whisper_full_n_tokens(e->ctx, i);
        float sum = 0;
        int cnt = 0;
        for (int k = 0; k < nt; k++) {
            whisper_token_data td = whisper_full_get_token_data(e->ctx, i, k);
            if (td.id >= whisper_token_eot(e->ctx)) continue;  // special tokens
            sum += td.p;
            cnt++;
        }
        out.push_back(text);
        out.push_back(std::to_string(whisper_full_get_segment_t0(e->ctx, i) * 10));  // centiseconds to ms
        out.push_back(std::to_string(whisper_full_get_segment_t1(e->ctx, i) * 10));
        out.push_back(std::to_string(cnt ? sum / cnt : 0.0f));
    }
    jobjectArray arr = env->NewObjectArray((jsize) out.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < out.size(); i++) {
        jstring s = env->NewStringUTF(out[i].c_str());
        env->SetObjectArrayElement(arr, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

/** Probability of each language in the first 30 s, as "code=probability" strings, highest first. */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_zellahq_whisper_WhisperNative_detectLanguage(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jint threads) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    if (whisper_pcm_to_mel(e->ctx, samples.data(), n, threads) != 0) return nullptr;
    std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
    // whisper.cpp fills real probabilities here (it applies exp itself), unlike WhisperKit's log-probs.
    if (whisper_lang_auto_detect(e->ctx, 0, threads, probs.data()) < 0) return nullptr;
    std::vector<std::pair<float, int>> ranked;
    for (int i = 0; i <= whisper_lang_max_id(); i++) ranked.emplace_back(probs[i], i);
    std::sort(ranked.rbegin(), ranked.rend());
    const int top = std::min<int>(5, (int) ranked.size());
    jobjectArray arr = env->NewObjectArray(top, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < top; i++) {
        std::string s = std::string(whisper_lang_str(ranked[i].second)) + "=" + std::to_string(ranked[i].first);
        jstring js = env->NewStringUTF(s.c_str());
        env->SetObjectArrayElement(arr, i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}
