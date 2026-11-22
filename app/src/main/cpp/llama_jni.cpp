// Alexia AI – lokálny jazykový model v mobile cez llama.cpp (GGUF: Qwen, Llama, Gemma, Mistral, …)
// Appka modelu nič nepridáva: žiadny skrytý systémový prompt, žiadne filtre. Posiela len to, čo je v chate.
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
#include <algorithm>
#include <atomic>
#include <cstring>
#include <map>
#include <string>
#include <vector>
#include "llama.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "AlexiaAI", __VA_ARGS__)

struct Stav {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_sampler* smpl = nullptr;
    std::vector<llama_token> cache;      // tokeny, ktoré už sú v KV cache (zdieľaný začiatok sa nespracúva znova)
    std::string tmpl;                    // chat šablóna z modelu
    std::atomic<bool> stop{false};
    std::map<std::string, llama_adapter_lora*> lory;   // načítané LoRA adaptéry (uvoľnia sa s modelom)
    std::string aktivneLory;                            // aby sa pamäť mazala len pri zmene
};

static std::string g_chyba;

static void logovanie(enum ggml_log_level l, const char* t, void*) {
    if (l >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "AlexiaAI", "%s", t);
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}

// UTF-8 text z Javy (správne aj emoji – GetStringUTFChars dáva "modified UTF-8")
static std::string jutf8(JNIEnv* env, jstring s) {
    if (!s) return "";
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

// koľko bajtov zo začiatku tvorí celé UTF-8 znaky (zvyšok počká na ďalší token)
static size_t celeUtf8(const std::string& s) {
    size_t n = s.size();
    for (size_t k = 1; k <= 4 && k <= n; k++) {
        unsigned char c = (unsigned char)s[n - k];
        if ((c & 0xC0) == 0x80) continue;                  // pokračovací bajt
        size_t dlzka = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        return dlzka > k ? n - k : n;
    }
    return n;
}

// rozhovor -> text podľa šablóny modelu (ak ju llama.cpp nepozná, použije ChatML ako Qwen)
std::string formatuj(const std::string& tmpl, const std::vector<std::string>& roly, const std::vector<std::string>& obsahy) {
    std::vector<llama_chat_message> msg;
    for (size_t i = 0; i < roly.size(); i++) msg.push_back({roly[i].c_str(), obsahy[i].c_str()});
    size_t odhad = 256; for (auto& o : obsahy) odhad += o.size() * 2 + 64;
    std::vector<char> buf(odhad);
    const char* t = tmpl.empty() ? nullptr : tmpl.c_str();
    if (llama_chat_apply_template(t, msg.data(), msg.size(), true, nullptr, 0) < 0) t = "chatml";
    int n = llama_chat_apply_template(t, msg.data(), msg.size(), true, buf.data(), (int32_t)buf.size());
    if (n < 0) return "";
    if ((size_t)n > buf.size()) {
        buf.resize((size_t)n);
        n = llama_chat_apply_template(t, msg.data(), msg.size(), true, buf.data(), (int32_t)buf.size());
    }
    return std::string(buf.data(), (size_t)n);
}

std::vector<llama_token> tokenizuj(const llama_vocab* vocab, const std::string& text, bool prvy) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), nullptr, 0, prvy, true);
    std::vector<llama_token> t((size_t)std::max(n, 0));
    if (n > 0) llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), t.data(), n, prvy, true);
    return t;
}

static void novyVzorkovac(Stav* s, float temp, float topP, int topK, float minP, float opak) {
    if (s->smpl) llama_sampler_free(s->smpl);
    auto p = llama_sampler_chain_default_params();
    s->smpl = llama_sampler_chain_init(p);
    if (opak > 1.0f) llama_sampler_chain_add(s->smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, opak, 0.0f, 0.0f));
    if (temp <= 0.0f) { llama_sampler_chain_add(s->smpl, llama_sampler_init_greedy()); return; }
    if (topK > 0) llama_sampler_chain_add(s->smpl, llama_sampler_init_top_k(topK));
    if (topP < 1.0f) llama_sampler_chain_add(s->smpl, llama_sampler_init_top_p(topP, 1));
    if (minP > 0.0f) llama_sampler_chain_add(s->smpl, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

#define F(ret, n) extern "C" JNIEXPORT ret JNICALL Java_sk_alexia_ai_Llama_##n

F(jlong, nacitaj)(JNIEnv* env, jobject, jstring cesta, jint nCtx, jint vlakna) {
    static bool init = false;
    if (!init) { llama_log_set(logovanie, nullptr); llama_backend_init(); init = true; }
    g_chyba.clear();
    std::string path = jstr(env, cesta);
    auto mp = llama_model_default_params();
#ifdef __ANDROID__
    mp.n_gpu_layers = 0;              // mobil: procesor
#else
    mp.n_gpu_layers = 999;            // Linux: celý model na grafiku (Vulkan), ak sa zmestí
#endif
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { g_chyba = "Model sa nedá načítať (nie je to GGUF, je poškodený, alebo ho llama.cpp nepozná)"; return 0; }
    auto cp = llama_context_default_params();
    int trenovany = llama_model_n_ctx_train(model);
    cp.n_ctx = (uint32_t)(trenovany > 0 ? std::min<int>(nCtx, trenovany) : nCtx);
    cp.n_batch = 512; cp.n_ubatch = 512;
    cp.n_threads = vlakna; cp.n_threads_batch = vlakna;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); g_chyba = "Na tento model nie je dosť pamäte – skús menší model alebo kratší kontext"; return 0; }
    auto* s = new Stav();
    s->model = model; s->ctx = ctx; s->vocab = llama_model_get_vocab(model);
    const char* t = llama_model_chat_template(model, nullptr);
    s->tmpl = t ? t : "";
    novyVzorkovac(s, 0.7f, 0.9f, 40, 0.05f, 1.1f);
    char popis[256] = {0}; llama_model_desc(model, popis, sizeof popis);
    LOG("Model: %s, kontext %u, šablóna %s", popis, llama_n_ctx(ctx), s->tmpl.empty() ? "(žiadna – ChatML)" : "z modelu");
    return (jlong)s;
}

F(jstring, chyba)(JNIEnv* env, jobject) { return env->NewStringUTF(g_chyba.c_str()); }

F(jstring, popis)(JNIEnv* env, jobject, jlong h) {
    auto* s = (Stav*)h; char b[256] = {0}; llama_model_desc(s->model, b, sizeof b);
    std::string r = std::string(b) + " · kontext " + std::to_string(llama_n_ctx(s->ctx));
    return env->NewStringUTF(r.c_str());
}

F(void, vzorkovanie)(JNIEnv*, jobject, jlong h, jfloat temp, jfloat topP, jint topK, jfloat minP, jfloat opak) {
    novyVzorkovac((Stav*)h, temp, topP, topK, minP, opak);
}

// výsledok: 0 hotovo, 1 zastavené, 2 dosiahnutý limit tokenov, -1 chyba, -2 rozhovor je dlhší ako kontext
F(jint, generuj)(JNIEnv* env, jobject, jlong h, jobjectArray roly, jobjectArray obsahy, jint maxTokenov, jobject cb) {
    auto* s = (Stav*)h; s->stop = false; g_chyba.clear();
    std::vector<std::string> r, o;
    for (jsize i = 0, n = env->GetArrayLength(roly); i < n; i++) {
        auto jr = (jstring)env->GetObjectArrayElement(roly, i); auto jo = (jstring)env->GetObjectArrayElement(obsahy, i);
        r.push_back(jstr(env, jr)); o.push_back(jutf8(env, jo));
        env->DeleteLocalRef(jr); env->DeleteLocalRef(jo);
    }
    std::string prompt = formatuj(s->tmpl, r, o);
    if (prompt.empty()) { g_chyba = "Šablónu rozhovoru sa nepodarilo použiť"; return -1; }
    auto tok = tokenizuj(s->vocab, prompt, true);
    const int nCtx = (int)llama_n_ctx(s->ctx);
    if ((int)tok.size() + 16 >= nCtx) return -2;

    // spoločný začiatok s tým, čo už je v pamäti modelu
    size_t keep = 0;
    while (keep < s->cache.size() && keep < tok.size() && s->cache[keep] == tok[keep]) keep++;
    if (keep == tok.size() && keep > 0) keep--;                      // posledný token treba spracovať kvôli výstupu
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos)keep, -1)) { llama_memory_clear(mem, true); keep = 0; }
    s->cache.assign(tok.begin(), tok.begin() + (long)keep);

    const int nBatch = (int)llama_n_batch(s->ctx);
    for (size_t i = keep; i < tok.size(); i += (size_t)nBatch) {
        if (s->stop) return 1;
        int n = (int)std::min<size_t>((size_t)nBatch, tok.size() - i);
        if (llama_decode(s->ctx, llama_batch_get_one(tok.data() + i, n)) != 0) {
            llama_memory_clear(mem, true); s->cache.clear(); g_chyba = "Spracovanie otázky zlyhalo"; return -1;
        }
        s->cache.insert(s->cache.end(), tok.begin() + (long)i, tok.begin() + (long)(i + (size_t)n));
    }

    jclass cls = env->GetObjectClass(cb);
    jmethodID mTok = env->GetMethodID(cls, "token", "([B)Z");
    llama_sampler_reset(s->smpl);
    std::string cakajuce; char piece[256];
    int vysledok = 2;
    for (int i = 0; i < maxTokenov; i++) {
        if (s->stop) { vysledok = 1; break; }
        if ((int)s->cache.size() + 1 >= nCtx) { vysledok = 2; break; }
        llama_token t = llama_sampler_sample(s->smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, t)) { vysledok = 0; break; }
        int n = llama_token_to_piece(s->vocab, t, piece, sizeof piece, 0, false);
        if (n > 0) cakajuce.append(piece, (size_t)n);
        size_t hotove = celeUtf8(cakajuce);
        if (hotove > 0) {
            jbyteArray a = env->NewByteArray((jsize)hotove);
            env->SetByteArrayRegion(a, 0, (jsize)hotove, (const jbyte*)cakajuce.data());
            jboolean dalej = env->CallBooleanMethod(cb, mTok, a);
            env->DeleteLocalRef(a);
            cakajuce.erase(0, hotove);
            if (!dalej) s->stop = true;
        }
        if (llama_decode(s->ctx, llama_batch_get_one(&t, 1)) != 0) { g_chyba = "Generovanie zlyhalo"; vysledok = -1; break; }
        s->cache.push_back(t);
    }
    env->DeleteLocalRef(cls);
    return vysledok;
}

// LoRA adaptéry pre chatový model (GGUF); prázdne pole = bez adaptérov. Vráti "" alebo text chyby.
F(jstring, nastavLory)(JNIEnv* env, jobject, jlong h, jobjectArray cesty, jfloatArray sily) {
    auto* s = (Stav*)h; if (!s) return env->NewStringUTF("Model nie je načítaný");
    jsize n = cesty ? env->GetArrayLength(cesty) : 0;
    std::vector<llama_adapter_lora*> a; std::vector<float> k; std::string kluc, chyba;
    jfloat* sl = n > 0 ? env->GetFloatArrayElements(sily, nullptr) : nullptr;
    for (jsize i = 0; i < n; i++) {
        auto j = (jstring)env->GetObjectArrayElement(cesty, i);
        std::string c = jstr(env, j); env->DeleteLocalRef(j);
        auto it = s->lory.find(c);
        llama_adapter_lora* l = it != s->lory.end() ? it->second : nullptr;
        if (!l) { l = llama_adapter_lora_init(s->model, c.c_str()); if (l) s->lory[c] = l; }
        if (!l) { chyba += "LoRA sa nedá načítať (nehodí sa k tomuto modelu alebo nie je GGUF): " + c + "\n"; continue; }
        a.push_back(l); k.push_back(sl ? sl[i] : 1.0f);
        kluc += c + "=" + std::to_string(k.back()) + ";";
    }
    if (sl) env->ReleaseFloatArrayElements(sily, sl, JNI_ABORT);
    if (kluc != s->aktivneLory) {
        if (llama_set_adapters_lora(s->ctx, a.empty() ? nullptr : a.data(), a.size(), k.empty() ? nullptr : k.data()) != 0) chyba += "LoRA sa nepodarilo použiť\n";
        // iné adaptéry = iné výsledky, uložená pamäť rozhovoru už neplatí
        llama_memory_clear(llama_get_memory(s->ctx), true); s->cache.clear();
        s->aktivneLory = kluc;
    }
    return env->NewStringUTF(chyba.c_str());
}

F(void, zastav)(JNIEnv*, jobject, jlong h) { if (h) ((Stav*)h)->stop = true; }

F(void, zabudni)(JNIEnv*, jobject, jlong h) {   // nový chat – vyprázdni pamäť modelu
    auto* s = (Stav*)h; llama_memory_clear(llama_get_memory(s->ctx), true); s->cache.clear();
}

F(void, uvolni)(JNIEnv*, jobject, jlong h) {
    auto* s = (Stav*)h; if (!s) return;
    if (s->smpl) llama_sampler_free(s->smpl);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}
