// Alexia AI – tvorba obrázkov v mobile cez stable-diffusion.cpp (MIT), úplne offline.
// Len text -> obrázok (žiadna úprava fotiek).
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
// Linux (SteamOS): log na stderr
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define __android_log_print(l, tag, ...) (fprintf(stderr, "[%s] ", tag), fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "stable-diffusion.h"

static std::string g_chyba;
static std::atomic<int> g_krok{0}, g_krokov{0};

// posledná chyba z logu – pripojí sa k hláške, aby nebolo len „see log for details“
static std::string g_posledna;
#ifndef __ANDROID__
static FILE* g_log = nullptr;   // PC: celý log do súboru sd.log (nastavLog)
#endif
static void logovanie(enum sd_log_level_t l, const char* t, void*) {
    if (l >= SD_LOG_ERROR && t) { g_posledna = t; while (!g_posledna.empty() && (g_posledna.back() == '\n' || g_posledna.back() == ' ')) g_posledna.pop_back(); }
#ifndef __ANDROID__
    if (g_log && t && l >= SD_LOG_INFO) { fputs(t, g_log); if (!*t || t[strlen(t) - 1] != '\n') fputc('\n', g_log); fflush(g_log); }
#endif
    if (l >= SD_LOG_WARN) __android_log_print(ANDROID_LOG_WARN, "AlexiaSD", "%s", t);
}
static void priebeh(int step, int steps, float, void*) { g_krok = step; g_krokov = steps; }

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    // UTF-8 (aj emoji) cez String.getBytes("UTF-8")
    jclass cls = env->GetObjectClass(s);
    jmethodID m = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring cs = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray)env->CallObjectMethod(s, m, cs);
    jsize n = env->GetArrayLength(arr);
    std::string r((size_t)n, '\0');
    env->GetByteArrayRegion(arr, 0, n, (jbyte*)r.data());
    env->DeleteLocalRef(arr); env->DeleteLocalRef(cs); env->DeleteLocalRef(cls);
    return r;
}

static bool ok(sd_status_t s) {
    if (s == SD_STATUS_OK) return true;
    const char* e = sd_get_last_error();
    g_chyba = (e && *e) ? e : ("chyba " + std::to_string((int)s));
    if (!g_posledna.empty() && g_chyba.find(g_posledna) == std::string::npos) g_chyba += " – " + g_posledna;
    g_posledna.clear();
    return false;
}

#define F(ret, n) extern "C" JNIEXPORT ret JNICALL Java_sk_alexia_ai_obrazky_Sd_##n

F(jlong, nacitaj)(JNIEnv* env, jobject, jstring cesta, jint vlakna) {
    static bool init = false;
    if (!init) { sd_set_log_callback(logovanie, nullptr); sd_set_progress_callback(priebeh, nullptr); init = true; }
    g_chyba.clear();
    std::string path = jstr(env, cesta);
    sd_ctx_opt_t* o = sd_ctx_opt_create();
    sd_ctx_t* ctx = nullptr;
    bool v = o && ok(sd_opt_set_string(o, "model_path", path.c_str()))
               && ok(sd_opt_set_int(o, "n_threads", vlakna))
               && ok(sd_ctx_create(o, &ctx));
    sd_opt_free(o);
    if (!v || !ctx) { if (g_chyba.empty()) g_chyba = "Model sa nedá načítať"; return 0; }
    if (!sd_ctx_supports_image_generation(ctx)) { sd_ctx_free(ctx); g_chyba = "Tento model nevie tvoriť obrázky"; return 0; }
    return (jlong)ctx;
}

F(jstring, chyba)(JNIEnv* env, jobject) { return env->NewStringUTF(g_chyba.c_str()); }
F(jstring, verzia)(JNIEnv* env, jobject, jlong h) { return env->NewStringUTF(sd_get_model_version_name((sd_ctx_t*)h)); }
F(jfloat, priebeh)(JNIEnv*, jobject) { int s = g_krokov.load(); return s > 0 ? (float)g_krok.load() / (float)s : 0.f; }
F(void, zrus)(JNIEnv*, jobject, jlong h) { if (h) sd_cancel_generation((sd_ctx_t*)h, SD_CANCEL_ALL); }

// vráti pixely ARGB (pre Bitmap) alebo null pri chybe; [šírka, výška] skutočného obrázka v rozmer
F(jintArray, generuj)(JNIEnv* env, jobject, jlong h, jstring prompt, jstring negativny, jint w, jint v,
                      jint kroky, jfloat cfg, jlong seed, jintArray rozmer) {
    auto* ctx = (sd_ctx_t*)h; g_chyba.clear(); g_krok = 0; g_krokov = kroky;
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    std::string p = jstr(env, prompt), n = jstr(env, negativny);
    sd_img_gen_opt_t* o = sd_img_gen_opt_create();
    sd_result_t* res = nullptr;
    bool good = o && ok(sd_opt_set_string(o, "prompt", p.c_str()))
                  && (n.empty() || ok(sd_opt_set_string(o, "negative_prompt", n.c_str())))
                  && ok(sd_opt_set_int(o, "width", w)) && ok(sd_opt_set_int(o, "height", v))
                  && ok(sd_opt_set_int(o, "seed", seed))
                  && ok(sd_opt_set_int(o, "sample.steps", kroky))
                  && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg))
                  && ok(sd_opt_set_string(o, "sample.method", "euler_a"))
                  && ok(sd_generate_image(ctx, o, &res));
    sd_opt_free(o);
    if (!good || !res || sd_result_get_image_count(res) == 0) { sd_result_free(res); if (g_chyba.empty()) g_chyba = "Obrázok sa nepodarilo vytvoriť"; return nullptr; }
    const sd_tensor_t* img = sd_result_get_image(res, 0);
    // [kanály, šírka, výška], kanály prekladané
    int64_t c = sd_tensor_dim(img, 0), sw = sd_tensor_dim(img, 1), sv = sd_tensor_dim(img, 2);
    const auto* d = (const uint8_t*)sd_tensor_data(img);
    if (sd_tensor_type(img) != SD_TENSOR_U8 || c < 3 || !d) { sd_result_free(res); g_chyba = "Neočakávaný formát obrázka"; return nullptr; }
    std::vector<jint> px((size_t)(sw * sv));
    for (int64_t i = 0; i < sw * sv; i++) {
        const uint8_t* q = d + i * c;
        px[(size_t)i] = (jint)(0xFF000000u | ((uint32_t)q[0] << 16) | ((uint32_t)q[1] << 8) | q[2]);
    }
    sd_result_free(res);
    jint r[2] = {(jint)sw, (jint)sv}; env->SetIntArrayRegion(rozmer, 0, 2, r);
    jintArray out = env->NewIntArray((jsize)px.size());
    env->SetIntArrayRegion(out, 0, (jsize)px.size(), px.data());
    g_krok = g_krokov.load();
    return out;
}

F(void, uvolni)(JNIEnv*, jobject, jlong h) { if (h) sd_ctx_free((sd_ctx_t*)h); }
