# Alexia AI

Chat s jazykovým modelom, ktorý beží **priamo v mobile, úplne offline** (llama.cpp).

- Akýkoľvek model **GGUF**: Qwen, Llama, Gemma, Mistral… (stiahnuť z odkazu alebo pridať zo súboru)
- Odpovede sa píšu postupne, pamäť rozhovoru medzi správami (rýchle pokračovanie)
- Appka nepridáva žiadne pravidlá ani skrytý systémový prompt – správanie určuje model a tvoj systémový prompt
- **Obrázky z textu** (stable-diffusion.cpp): Stable Diffusion 1.x/2.x, offline v mobile, uloženie do galérie
- Modely aj chat sú len v priečinku appky, appka nemá prístup k iným súborom

**Stiahnuť:** [AlexiaAI.apk](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk)

Do mobilu sa hodia modely 0.5B–4B v kvantizácii Q4_0 alebo Q4_K_M.
