// Test úpravy cez seed: rovnaké volania ako appka (sd_linux.cpp generujEx / generujZPredlohy), bez JNI.
#include "stable-diffusion.h"
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

static bool ok(sd_status_t s) { if (s != SD_STATUS_OK) { fprintf(stderr, "chyba %d: %s\n", (int)s, sd_get_last_error()); return false; } return true; }
static void logf(sd_log_level_t, const char* t, void*) { fputs(t, stderr); }

static std::vector<uint8_t> gen(sd_ctx_t* ctx, const char* p, int w, int h, int steps, long seed, const std::vector<uint8_t>* init, float sila, const char* out) {
    sd_img_gen_opt_t* o = sd_img_gen_opt_create();
    sd_result_t* res = nullptr;
    sd_tensor_t* t = nullptr;
    int64_t tvar[3] = {3, w, h};
    bool g = ok(sd_opt_set_string(o, "prompt", p)) && ok(sd_opt_set_int(o, "width", w)) && ok(sd_opt_set_int(o, "height", h))
          && ok(sd_opt_set_int(o, "seed", seed)) && ok(sd_opt_set_int(o, "sample.steps", steps)) && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", 1.0f));
    if (g && init) {
        t = sd_tensor_create(SD_TENSOR_U8, 3, tvar, init->data(), init->size());
        g = ok(sd_opt_set_tensor(o, "init_image", t)) && ok(sd_opt_set_float(o, "strength", sila))
         && ok(sd_opt_set_string(o, "sample.args", "strength_as_noise_level=true,force_first_sigma=true"));
    }
    g = g && ok(sd_generate_image(ctx, o, &res));
    sd_opt_free(o); if (t) sd_tensor_free(t);
    std::vector<uint8_t> rgb;
    if (!g || !res || sd_result_get_image_count(res) == 0) { fprintf(stderr, "generovanie zlyhalo\n"); return rgb; }
    const sd_tensor_t* img = sd_result_get_image(res, 0);
    int64_t c = sd_tensor_dim(img, 0), sw = sd_tensor_dim(img, 1), sv = sd_tensor_dim(img, 2);
    const uint8_t* d = (const uint8_t*)sd_tensor_data(img);
    rgb.resize(sw * sv * 3);
    for (int64_t i = 0; i < sw * sv; i++) for (int k = 0; k < 3; k++) rgb[i * 3 + k] = d[i * c + k];
    stbi_write_png(out, (int)sw, (int)sv, 3, rgb.data(), (int)sw * 3);
    sd_result_free(res);
    return rgb;
}

static double rozdiel(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    double s = 0; for (size_t i = 0; i < a.size() && i < b.size(); i++) s += std::abs((int)a[i] - (int)b[i]); return s / a.size();
}

int main(int argc, char** argv) {
    sd_set_log_callback(logf, nullptr);
    sd_ctx_opt_t* o = sd_ctx_opt_create();
    bool v = ok(sd_opt_set_string(o, "diffusion_model_path", argv[1])) && ok(sd_opt_set_string(o, "llm_path", argv[2]))
          && ok(sd_opt_set_string(o, "vae_path", argv[3])) && ok(sd_opt_set_int(o, "n_threads", 4));
    sd_ctx_t* ctx = nullptr;
    if (v) ok(sd_ctx_create(o, &ctx));
    sd_opt_free(o);
    if (!ctx) return 1;
    // voliteľne: kroky, rozmer (FLUX.3 Klein: 16 krokov); s 4. argumentom len pôvodný + jedna úprava
    const int K = argc > 4 ? atoi(argv[4]) : 8;
    const int W = argc > 5 ? atoi(argv[5]) : 384, H = W;
    const char* p = "a red apple on a wooden table, photo";
    if (argc > 4) {
        auto a = gen(ctx, p, W, H, K, 12345, nullptr, 0, "a_povodny.png");
        std::string p2 = std::string(p) + ", a small green leaf on the apple";
        auto b = gen(ctx, p2.c_str(), W, H, K, 12345, &a, 0.85f, "b_uprava_085.png");
        auto d = gen(ctx, "portrait photo of a young woman with long brown hair, freckles, soft window light, 85mm lens", W, H, K, 777, nullptr, 0, "d_portret.png");
        printf("ROZDIEL 0.85: %.1f\n", rozdiel(a, b));
        return 0;
    }
    auto a = gen(ctx, p, W, H, K, 12345, nullptr, 0, "a_povodny.png");
    auto c = gen(ctx, p, W, H, K, 54321, nullptr, 0, "c_iny_seed.png");
    std::string p2 = std::string(p) + ", a small green leaf on the apple";
    auto b7 = gen(ctx, p2.c_str(), W, H, 12, 12345, &a, 0.7f, "b_uprava_070.png");
    auto b8 = gen(ctx, p2.c_str(), W, H, 12, 12345, &a, 0.8f, "b_uprava_080.png");
    auto b9 = gen(ctx, p2.c_str(), W, H, 12, 12345, &a, 0.9f, "b_uprava_090.png");
    printf("ROZDIEL 0.7: %.1f  0.8: %.1f  0.9: %.1f  iny seed: %.1f\n", rozdiel(a, b7), rozdiel(a, b8), rozdiel(a, b9), rozdiel(a, c));
    return 0;
}
