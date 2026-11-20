# Alexia AI

Chat s jazykovým modelom, ktorý beží **priamo v mobile, úplne offline** (llama.cpp).

- Akýkoľvek model **GGUF**: Qwen, Llama, Gemma, Mistral… (stiahnuť z odkazu alebo pridať zo súboru)
- Odpovede sa píšu postupne, pamäť rozhovoru medzi správami (rýchle pokračovanie)
- Appka nepridáva žiadne pravidlá ani skrytý systémový prompt – správanie určuje model a tvoj systémový prompt
- **Obrázky z textu** (stable-diffusion.cpp): Stable Diffusion 1.x/2.x, offline v mobile, uloženie do galérie
- Modely aj chat sú len v priečinku appky, appka nemá prístup k iným súborom

**Stiahnuť:**
- Android: [AlexiaAI.apk](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk)
- Linux / SteamOS (x86_64, Vulkan): [AlexiaAI-x86_64.AppImage](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-x86_64.AppImage) – stiahni a dvakrát klikni (alebo [ZIP](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-Linux.zip))

Do mobilu sa hodia modely 0.5B–4B v kvantizácii Q4_0 alebo Q4_K_M.

## Inštalácia na Linux / SteamOS (Legion Go S, Steam Deck…)

V Desktop Mode otvor **Konsole**, vlož tento riadok (Ctrl+Shift+V) a stlač Enter:

```bash
cd ~ && curl -L -o AlexiaAI.AppImage https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-x86_64.AppImage && chmod +x AlexiaAI.AppImage && ./AlexiaAI.AppImage
```

Stiahne appku do domovského priečinka, povolí jej spustenie a spustí ju. Pri prvom spustení sa Alexia AI
sama pridá do **ponuky aplikácií**. Do Steamu: *Steam → Pridať hru → Pridať hru mimo Steamu → Alexia AI*.

Nové verzie appka ponúkne sama (tlačidlo *Aktualizovať* – stiahne sa, vymení a reštartuje).

> Pozor: tlačidlo **Code → Download ZIP** na GitHube sťahuje zdrojový kód, nie hotovú appku.

## Inštalácia na Android

Stiahni [AlexiaAI.apk](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk) a otvor ho.
Nové verzie appka ponúkne sama (APK stiahne prehliadač).
