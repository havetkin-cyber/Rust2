// Alexia AI pre Linux: všetko z mobilnej verzie (sd_jni.cpp) + vylepšenie fotky, ktoré je len na Linuxe.
#include "../../../obrazky/src/main/cpp/sd_jni.cpp"

// nekonečná textúra (obrázok na seba nadväzuje zo všetkých strán) – platí pre ďalšie generovanie
static bool g_kruh = false;
F(void, nastavTexturu)(JNIEnv*, jobject, jboolean x) { g_kruh = x; }
static bool kruhove(sd_img_gen_opt_t* o) {
    return !g_kruh || (sd_opt_set_bool(o, "circular_x", true) == SD_STATUS_OK && sd_opt_set_bool(o, "circular_y", true) == SD_STATUS_OK);
}

// ---------------------------------------------------------------- vylepšenie fotky: zväčšenie (Real-ESRGAN)
// Obsah fotky sa nemení – model len doplní detaily pri zväčšení.

F(jlong, upscalerNacitaj)(JNIEnv* env, jobject, jstring cesta, jint vlakna) {
    static bool init = false;
    if (!init) { sd_set_log_callback(logovanie, nullptr); sd_set_progress_callback(priebeh, nullptr); init = true; }
    g_chyba.clear();
    std::string path = jstr(env, cesta);
    sd_upscaler_opt_t* o = sd_upscaler_opt_create();
    sd_upscaler_t* u = nullptr;
    bool v = o && ok(sd_opt_set_string(o, "model_path", path.c_str()))
               && ok(sd_opt_set_int(o, "n_threads", vlakna))
               && ok(sd_upscaler_create(o, &u));
    sd_opt_free(o);
    if (!v || !u) { if (g_chyba.empty()) g_chyba = "Model na vylepšenie sa nedá načítať"; return 0; }
    return (jlong)u;
}

F(jint, upscalerNasobok)(JNIEnv*, jobject, jlong h) { return h ? sd_upscaler_get_scale((sd_upscaler_t*)h) : 0; }

// vstup ARGB (šírka × výška), výstup ARGB zväčšený; skutočný rozmer do [rozmer]
F(jintArray, zvacsi)(JNIEnv* env, jobject, jlong h, jintArray argb, jint w, jint v, jintArray rozmer) {
    g_chyba.clear(); g_krok = 0; g_krokov = 0;
    auto* u = (sd_upscaler_t*)h;
    jsize n = env->GetArrayLength(argb);
    if (n != w * v) { g_chyba = "Zlý rozmer obrázka"; return nullptr; }
    std::vector<jint> vstup((size_t)n); env->GetIntArrayRegion(argb, 0, n, vstup.data());
    std::vector<uint8_t> rgb((size_t)n * 3);
    for (jsize i = 0; i < n; i++) {
        uint32_t p = (uint32_t)vstup[(size_t)i];
        rgb[(size_t)i * 3] = (uint8_t)(p >> 16); rgb[(size_t)i * 3 + 1] = (uint8_t)(p >> 8); rgb[(size_t)i * 3 + 2] = (uint8_t)p;
    }
    int64_t tvar[3] = {3, w, v};
    sd_tensor_t* t = sd_tensor_create(SD_TENSOR_U8, 3, tvar, rgb.data(), rgb.size());
    sd_upscale_opt_t* o = sd_upscale_opt_create();
    sd_result_t* res = nullptr;
    bool good = t && o && ok(sd_opt_set_tensor(o, "image", t)) && ok(sd_upscale(u, o, &res));
    sd_opt_free(o); sd_tensor_free(t);
    if (!good || !res || sd_result_get_image_count(res) == 0) { sd_result_free(res); if (g_chyba.empty()) g_chyba = "Vylepšenie zlyhalo"; return nullptr; }
    const sd_tensor_t* img = sd_result_get_image(res, 0);
    int64_t c = sd_tensor_dim(img, 0), sw = sd_tensor_dim(img, 1), sv = sd_tensor_dim(img, 2);
    const auto* d = (const uint8_t*)sd_tensor_data(img);
    if (sd_tensor_type(img) != SD_TENSOR_U8 || c < 3 || !d) { sd_result_free(res); g_chyba = "Neočakávaný formát obrázka"; return nullptr; }
    jintArray out = env->NewIntArray((jsize)(sw * sv));
    if (!out) { sd_result_free(res); g_chyba = "Nedostatok pamäte"; return nullptr; }
    std::vector<jint> riadok((size_t)sw);
    for (int64_t y = 0; y < sv; y++) {
        for (int64_t x = 0; x < sw; x++) {
            const uint8_t* q = d + (y * sw + x) * c;
            riadok[(size_t)x] = (jint)(0xFF000000u | ((uint32_t)q[0] << 16) | ((uint32_t)q[1] << 8) | q[2]);
        }
        env->SetIntArrayRegion(out, (jsize)(y * sw), (jsize)sw, riadok.data());
    }
    sd_result_free(res);
    jint r[2] = {(jint)sw, (jint)sv}; env->SetIntArrayRegion(rozmer, 0, 2, r);
    return out;
}

F(void, upscalerUvolni)(JNIEnv*, jobject, jlong h) { if (h) sd_upscaler_free((sd_upscaler_t*)h); }

// ---------------------------------------------------------------- modely z viacerých súborov (Z-Image, FLUX…)
// kľúče: model_path, diffusion_model_path, vae_path, llm_path, clip_l_path, clip_g_path, t5xxl_path
F(jlong, nacitajViac)(JNIEnv* env, jobject, jobjectArray kluce, jobjectArray hodnoty, jint vlakna, jboolean fa) {
    static bool init = false;
    if (!init) { sd_set_log_callback(logovanie, nullptr); sd_set_progress_callback(priebeh, nullptr); init = true; }
    g_chyba.clear();
    sd_ctx_opt_t* o = sd_ctx_opt_create();
    bool v = o && ok(sd_opt_set_int(o, "n_threads", vlakna));
    for (jsize i = 0, n = env->GetArrayLength(kluce); v && i < n; i++) {
        auto jk = (jstring)env->GetObjectArrayElement(kluce, i); auto jh = (jstring)env->GetObjectArrayElement(hodnoty, i);
        std::string k = jstr(env, jk), h = jstr(env, jh);
        env->DeleteLocalRef(jk); env->DeleteLocalRef(jh);
        v = ok(sd_opt_set_string(o, k.c_str(), h.c_str()));
    }
    if (v && fa) v = ok(sd_opt_set_bool(o, "diffusion_flash_attn", true));
    sd_ctx_t* ctx = nullptr;
    v = v && ok(sd_ctx_create(o, &ctx));
    sd_opt_free(o);
    if (!v || !ctx) { if (g_chyba.empty()) g_chyba = "Model sa nedá načítať"; return 0; }
    if (!sd_ctx_supports_image_generation(ctx)) { sd_ctx_free(ctx); g_chyba = "Tento model nevie tvoriť obrázky"; return 0; }
    return (jlong)ctx;
}

// ako generuj, ale so zvoleným samplerom ("" alebo "auto" = predvolený pre model)
F(jintArray, generujEx)(JNIEnv* env, jobject, jlong h, jstring prompt, jstring negativny, jint w, jint v,
                        jint kroky, jfloat cfg, jlong seed, jstring metoda, jintArray rozmer) {
    auto* ctx = (sd_ctx_t*)h; g_chyba.clear(); g_krok = 0; g_krokov = kroky;
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    std::string p = jstr(env, prompt), n = jstr(env, negativny), m = jstr(env, metoda);
    sd_img_gen_opt_t* o = sd_img_gen_opt_create();
    sd_result_t* res = nullptr;
    bool good = o && ok(sd_opt_set_string(o, "prompt", p.c_str()))
                  && (n.empty() || ok(sd_opt_set_string(o, "negative_prompt", n.c_str())))
                  && ok(sd_opt_set_int(o, "width", w)) && ok(sd_opt_set_int(o, "height", v))
                  && ok(sd_opt_set_int(o, "seed", seed))
                  && ok(sd_opt_set_int(o, "sample.steps", kroky))
                  && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg)) && kruhove(o)
                  && (m.empty() || m == "auto" || ok(sd_opt_set_string(o, "sample.method", m.c_str())))
                  && ok(sd_generate_image(ctx, o, &res));
    sd_opt_free(o);
    if (!good || !res || sd_result_get_image_count(res) == 0) { sd_result_free(res); if (g_chyba.empty()) g_chyba = "Obrázok sa nepodarilo vytvoriť"; return nullptr; }
    const sd_tensor_t* img = sd_result_get_image(res, 0);
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

// úprava obrázka vytvoreného v appke: predloha (ARGB) + nový popis, sila 0..1 (menej = drží sa predlohy)
F(jintArray, generujZPredlohy)(JNIEnv* env, jobject, jlong h, jstring prompt, jstring negativny, jint w, jint v,
                               jint kroky, jfloat cfg, jlong seed, jstring metoda, jintArray argb, jfloat sila, jintArray rozmer) {
    auto* ctx = (sd_ctx_t*)h; g_chyba.clear(); g_krok = 0; g_krokov = kroky;
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    std::string p = jstr(env, prompt), n = jstr(env, negativny), m = jstr(env, metoda);
    jsize pocet = env->GetArrayLength(argb);
    if (pocet != w * v) { g_chyba = "Zlý rozmer predlohy"; return nullptr; }
    std::vector<jint> vstup((size_t)pocet); env->GetIntArrayRegion(argb, 0, pocet, vstup.data());
    std::vector<uint8_t> rgb((size_t)pocet * 3);
    for (jsize i = 0; i < pocet; i++) {
        uint32_t q = (uint32_t)vstup[(size_t)i];
        rgb[(size_t)i * 3] = (uint8_t)(q >> 16); rgb[(size_t)i * 3 + 1] = (uint8_t)(q >> 8); rgb[(size_t)i * 3 + 2] = (uint8_t)q;
    }
    int64_t tvar[3] = {3, w, v};
    sd_tensor_t* t = sd_tensor_create(SD_TENSOR_U8, 3, tvar, rgb.data(), rgb.size());
    sd_img_gen_opt_t* o = sd_img_gen_opt_create();
    sd_result_t* res = nullptr;
    bool good = t && o && ok(sd_opt_set_string(o, "prompt", p.c_str()))
                  && (n.empty() || ok(sd_opt_set_string(o, "negative_prompt", n.c_str())))
                  && ok(sd_opt_set_int(o, "width", w)) && ok(sd_opt_set_int(o, "height", v))
                  && ok(sd_opt_set_int(o, "seed", seed))
                  && ok(sd_opt_set_int(o, "sample.steps", kroky))
                  && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg)) && kruhove(o)
                  && (m.empty() || m == "auto" || ok(sd_opt_set_string(o, "sample.method", m.c_str())))
                  && ok(sd_opt_set_tensor(o, "init_image", t))
                  && ok(sd_opt_set_float(o, "strength", sila))
                  && ok(sd_generate_image(ctx, o, &res));
    sd_opt_free(o);
    if (t) sd_tensor_free(t);
    if (!good || !res || sd_result_get_image_count(res) == 0) { sd_result_free(res); if (g_chyba.empty()) g_chyba = "Obrázok sa nepodarilo upraviť"; return nullptr; }
    const sd_tensor_t* img = sd_result_get_image(res, 0);
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
