// Alexia AI pre Linux: všetko z mobilnej verzie (sd_jni.cpp) + vylepšenie fotky, ktoré je len na Linuxe.
#include "../../../obrazky/src/main/cpp/sd_jni.cpp"

// log stable-diffusion.cpp do súboru (napr. %LOCALAPPDATA%\\AlexiaAI\\sd.log)
F(void, nastavLog)(JNIEnv* env, jobject, jstring cesta) {
#ifndef __ANDROID__
    std::string c = jstr(env, cesta);
    if (g_log) { fclose(g_log); g_log = nullptr; }
    g_log = fopen(c.c_str(), "w");
#else
    (void)env; (void)cesta;
#endif
    static bool init = false;
    if (!init) { sd_set_log_callback(logovanie, nullptr); sd_set_progress_callback(priebeh, nullptr); init = true; }
}

// ---------------------------------------------------------------- LoRA adaptéry (platia pre ďalšie obrázky aj videá)
static std::vector<std::string> g_lora_cesty;
static std::vector<float> g_lora_sily;
F(void, nastavLory)(JNIEnv* env, jobject, jobjectArray cesty, jfloatArray sily) {
    g_lora_cesty.clear(); g_lora_sily.clear();
    jsize n = cesty ? env->GetArrayLength(cesty) : 0;
    jfloat* s = n > 0 ? env->GetFloatArrayElements(sily, nullptr) : nullptr;
    for (jsize i = 0; i < n; i++) {
        auto j = (jstring)env->GetObjectArrayElement(cesty, i);
        g_lora_cesty.push_back(jstr(env, j)); env->DeleteLocalRef(j);
        g_lora_sily.push_back(s ? s[i] : 1.0f);
    }
    if (s) env->ReleaseFloatArrayElements(sily, s, JNI_ABORT);
}
static bool lory(sd_opt_t* o) {
    if (g_lora_cesty.empty()) return true;
    std::vector<sd_lora_t> l(g_lora_cesty.size());
    for (size_t i = 0; i < l.size(); i++) { l[i].is_high_noise = false; l[i].multiplier = g_lora_sily[i]; l[i].path = g_lora_cesty[i].c_str(); }
    return ok(sd_opt_set_loras(o, "loras", l.data(), l.size()));
}

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
    if (!sd_ctx_supports_image_generation(ctx) && !sd_ctx_supports_video_generation(ctx)) { sd_ctx_free(ctx); g_chyba = "Tento model nevie tvoriť obrázky ani video"; return 0; }
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
                  && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg)) && kruhove(o) && lory(o)
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
                  && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg)) && kruhove(o) && lory(o)
                  && (m.empty() || m == "auto" || ok(sd_opt_set_string(o, "sample.method", m.c_str())))
                  && ok(sd_opt_set_tensor(o, "init_image", t))
                  && ok(sd_opt_set_float(o, "strength", sila))
                  // sila = skutočná úroveň šumu (0.4 = 60 % predlohy ostane). Bez toho by pri Z-Image/FLUX
                  // aj „0.6“ začínalo takmer z čistého šumu a vznikol by úplne nový obrázok.
                  && ok(sd_opt_set_string(o, "sample.args", "strength_as_noise_level=true,force_first_sigma=true"))
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

// ---------------------------------------------------------------- referenčné obrázky (FLUX.3 Klein): prenos štýlu, úpravy podľa vzoru
// refPx = pole ARGB obrázkov, refW/refV = ich rozmery; výsledok ARGB, rozmer = {w, h}
F(jintArray, generujSReferenciami)(JNIEnv* env, jobject, jlong h, jstring prompt, jstring negativny, jint w, jint v,
                                   jint kroky, jfloat cfg, jlong seed, jstring metoda, jobjectArray refPx, jintArray refW, jintArray refV, jintArray rozmer) {
    auto* ctx = (sd_ctx_t*)h; g_chyba.clear(); g_krok = 0; g_krokov = kroky;
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    std::string p = jstr(env, prompt), n = jstr(env, negativny), m = jstr(env, metoda);
    jsize pocetRef = env->GetArrayLength(refPx);
    std::vector<jint> ws((size_t)pocetRef), vs((size_t)pocetRef);
    env->GetIntArrayRegion(refW, 0, pocetRef, ws.data()); env->GetIntArrayRegion(refV, 0, pocetRef, vs.data());
    std::vector<std::vector<uint8_t>> data((size_t)pocetRef);
    std::vector<sd_tensor_t*> tenzory;
    for (jsize r = 0; r < pocetRef; r++) {
        auto arr = (jintArray)env->GetObjectArrayElement(refPx, r);
        jsize pocet = env->GetArrayLength(arr);
        if (pocet != ws[(size_t)r] * vs[(size_t)r]) { env->DeleteLocalRef(arr); g_chyba = "Zlý rozmer referenčného obrázka"; for (auto* t : tenzory) sd_tensor_free(t); return nullptr; }
        std::vector<jint> vstup((size_t)pocet); env->GetIntArrayRegion(arr, 0, pocet, vstup.data()); env->DeleteLocalRef(arr);
        auto& rgb = data[(size_t)r]; rgb.resize((size_t)pocet * 3);
        for (jsize i = 0; i < pocet; i++) {
            uint32_t q = (uint32_t)vstup[(size_t)i];
            rgb[(size_t)i * 3] = (uint8_t)(q >> 16); rgb[(size_t)i * 3 + 1] = (uint8_t)(q >> 8); rgb[(size_t)i * 3 + 2] = (uint8_t)q;
        }
        int64_t tvar[3] = {3, ws[(size_t)r], vs[(size_t)r]};
        tenzory.push_back(sd_tensor_create(SD_TENSOR_U8, 3, tvar, rgb.data(), rgb.size()));
    }
    sd_img_gen_opt_t* o = sd_img_gen_opt_create();
    sd_result_t* res = nullptr;
    bool good = o && ok(sd_opt_set_string(o, "prompt", p.c_str()))
                  && (n.empty() || ok(sd_opt_set_string(o, "negative_prompt", n.c_str())))
                  && ok(sd_opt_set_int(o, "width", w)) && ok(sd_opt_set_int(o, "height", v))
                  && ok(sd_opt_set_int(o, "seed", seed))
                  && ok(sd_opt_set_int(o, "sample.steps", kroky))
                  && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg)) && lory(o)
                  && (m.empty() || m == "auto" || ok(sd_opt_set_string(o, "sample.method", m.c_str())))
                  && (tenzory.empty() || ok(sd_opt_set_tensors(o, "ref_images", tenzory.data(), tenzory.size())))
                  && ok(sd_generate_image(ctx, o, &res));
    sd_opt_free(o);
    for (auto* t : tenzory) if (t) sd_tensor_free(t);
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
    jint rr[2] = {(jint)sw, (jint)sv}; env->SetIntArrayRegion(rozmer, 0, 2, rr);
    jintArray out = env->NewIntArray((jsize)px.size());
    env->SetIntArrayRegion(out, 0, (jsize)px.size(), px.data());
    g_krok = g_krokov.load();
    return out;
}

// ---------------------------------------------------------------- video AI (Wan 2.1): text → krátke video
// vráti RGB snímky za sebou (w*h*3*T bajtov), rozmer = {w, h, T, fps}
F(jbyteArray, generujVideo)(JNIEnv* env, jobject, jlong h, jstring prompt, jstring negativny, jint w, jint v, jint snimok,
                            jint kroky, jfloat cfg, jfloat posun, jlong seed, jstring metoda, jintArray predloha, jintArray rozmer) {
    auto* ctx = (sd_ctx_t*)h; g_chyba.clear(); g_krok = 0; g_krokov = kroky;
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    if (!sd_ctx_supports_video_generation(ctx)) { g_chyba = "Načítaný model nevie robiť video"; return nullptr; }
    // voliteľný prvý obrázok (rozhýbanie obrázka – Wan 2.2 TI2V); ARGB w×v
    std::vector<uint8_t> prgb;
    sd_tensor_t* pt = nullptr;
    if (predloha) {
        jsize pn = env->GetArrayLength(predloha);
        if (pn != w * v) { g_chyba = "Zlý rozmer obrázka"; return nullptr; }
        std::vector<jint> px((size_t)pn); env->GetIntArrayRegion(predloha, 0, pn, px.data());
        prgb.resize((size_t)pn * 3);
        for (jsize i = 0; i < pn; i++) { uint32_t q = (uint32_t)px[(size_t)i]; prgb[(size_t)i * 3] = (uint8_t)(q >> 16); prgb[(size_t)i * 3 + 1] = (uint8_t)(q >> 8); prgb[(size_t)i * 3 + 2] = (uint8_t)q; }
        int64_t tvar[3] = {3, w, v};
        pt = sd_tensor_create(SD_TENSOR_U8, 3, tvar, prgb.data(), prgb.size());
    }
    std::string p = jstr(env, prompt), n = jstr(env, negativny), m = jstr(env, metoda);
    sd_vid_gen_opt_t* o = sd_vid_gen_opt_create();
    sd_result_t* res = nullptr;
    bool good = o && ok(sd_opt_set_string(o, "prompt", p.c_str()))
                  && (n.empty() || ok(sd_opt_set_string(o, "negative_prompt", n.c_str())))
                  && ok(sd_opt_set_int(o, "width", w)) && ok(sd_opt_set_int(o, "height", v))
                  && ok(sd_opt_set_int(o, "seed", seed))
                  && ok(sd_opt_set_int(o, "video_frames", snimok))
                  && ok(sd_opt_set_int(o, "sample.steps", kroky))
                  && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg))
                  && ok(sd_opt_set_float(o, "sample.flow_shift", posun))
                  && lory(o)
                  // VAE po kúskoch: skladanie videa sa zmestí aj do 6 GB grafiky (inak by padlo alebo muselo ísť na CPU)
                  && ok(sd_opt_set_bool(o, "vae_tiling.enabled", true))
                  && (m.empty() || m == "auto" || ok(sd_opt_set_string(o, "sample.method", m.c_str())))
                  && (!pt || ok(sd_opt_set_tensor(o, "init_image", pt)))
                  && ok(sd_generate_video(ctx, o, &res));
    sd_opt_free(o);
    if (pt) sd_tensor_free(pt);
    const sd_video_t* vid = (good && res) ? sd_result_get_video(res) : nullptr;
    if (!vid || !vid->data) { sd_result_free(res); if (g_chyba.empty()) g_chyba = "Video sa nepodarilo vytvoriť"; return nullptr; }
    const sd_tensor_t* t = vid->data;
    int64_t c = sd_tensor_dim(t, 0), sw = sd_tensor_dim(t, 1), sv = sd_tensor_dim(t, 2), st = sd_tensor_dim(t, 3);
    const auto* d = (const uint8_t*)sd_tensor_data(t);
    if (sd_tensor_type(t) != SD_TENSOR_U8 || c < 3 || !d || st < 1) { sd_result_free(res); g_chyba = "Neočakávaný formát videa"; return nullptr; }
    size_t pocet = (size_t)(sw * sv * st);
    std::vector<jbyte> rgb(pocet * 3);
    for (size_t i = 0; i < pocet; i++) { rgb[i * 3] = (jbyte)d[i * c]; rgb[i * 3 + 1] = (jbyte)d[i * c + 1]; rgb[i * 3 + 2] = (jbyte)d[i * c + 2]; }
    jint r[4] = {(jint)sw, (jint)sv, (jint)st, (jint)(vid->fps > 0 ? vid->fps : 16)};
    sd_result_free(res);
    env->SetIntArrayRegion(rozmer, 0, 4, r);
    jbyteArray out = env->NewByteArray((jsize)rgb.size());
    env->SetByteArrayRegion(out, 0, (jsize)rgb.size(), rgb.data());
    g_krok = g_krokov.load();
    return out;
}
