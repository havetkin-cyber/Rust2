# Alexia AI

Lokálny AI chat a tvorba obrázkov – beží **priamo v tvojom zariadení, offline** (llama.cpp, stable-diffusion.cpp).
Mobil (Android), Linux / SteamOS (Legion Go S, Steam Deck) a Windows PC.

## Stiahnuť

| Zariadenie | Súbor |
|---|---|
| Android | [AlexiaAI.apk](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk) |
| Linux / SteamOS (x86_64, Vulkan) | [AlexiaAI-x86_64.AppImage](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-x86_64.AppImage) (alebo [ZIP](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-Linux.zip)) |
| Windows 10/11 (x64, Vulkan) | [AlexiaAI-Windows.zip](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-Windows.zip) |

> Pozor: tlačidlo **Code → Download ZIP** na GitHube sťahuje zdrojový kód, nie hotovú appku.

## Čo appka vie

**Chat**
- Akýkoľvek model **GGUF** (Qwen, Llama, Gemma, Mistral…) – z katalógu jedným tlačidlom, z odkazu Hugging Face (stačí odkaz na súbor alebo na celý repozitár) alebo zo súboru
- Odpovede sa píšu postupne, premýšľanie modelov (Qwen3) sa dá rozbaliť
- **Kód** v odpovediach v tmavom okne ako vo VS Code s tlačidlom *Kopírovať*
- **Jazyk odpovedí** – automaticky, slovensky alebo anglicky
- **Hlas** – odpovede nahlas (PC/Linux: ženský slovenský hlas *Lili*, offline; mobil: slovenský hlas telefónu)
- Appka nepridáva žiadny skrytý systémový prompt – správanie určuje model a tvoje nastavenia

**Obrázky**
- Z textu, offline: PC/Linux – Z-Image Turbo, FLUX.1 schnell, SD 1.5 (sťahujú sa jedným tlačidlom); mobil – Stable Diffusion 1.5
- **Pozadia** na výber (príroda, pláž, sci-fi, cyberpunk, vesmír, náhodné… alebo vlastné)
- Obrázky aj **priamo v chate** („nakresli …“ alebo ikonka štetca)
- Vlastný obrázok ako **pozadie chatu**

**Vylepšenie fotky**
- AI zväčšenie (Real-ESRGAN) 2×, 4×, 4K (PC/Linux aj **8K**) – obsah fotky sa nemení
- Automatický jas, živšie farby, doostrenie

**Prepojenie zariadení**
- Na Legion Go S / PC zapni *Nastavenia → Zdieľať môj model v sieti* – ukáže adresu a PIN
- V mobile (alebo na inom PC) zapni *Použiť model z iného zariadenia*, ťukni *Nájsť v sieti* alebo zadaj adresu a PIN
- Mobil potom chatuje s veľkým modelom (napr. 14B) a kreslí obrázky na Legion Go S
- Cez internet: nainštaluj [Tailscale](https://tailscale.com) (zadarmo) na obe zariadenia a zadaj adresu `100.x.x.x`

**Ovládanie (PC / Legion Go S)**
- Záložky *Chat · Obrázky · Vylepšiť · Modely · Nastavenia*, prepínanie **L1/R1** alebo **L2/R2**, posúvanie páčkou, **B** = späť
- Klávesnica: Ctrl+Tab, F1–F5, PageUp/PageDown, Esc
- Pole na písanie sa posunie nad klávesnicu na obrazovke (Steam)

**Aktualizácie** – appka ponúkne novú verziu sama, alebo *Nastavenia → Skontrolovať a stiahnuť aktualizácie*.

## Inštalácia na Linux / SteamOS (Legion Go S, Steam Deck…)

V Desktop Mode otvor **Konsole**, vlož tento riadok (Ctrl+Shift+V) a stlač Enter:

```bash
cd ~ && curl -L -o AlexiaAI.AppImage https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-x86_64.AppImage && chmod +x AlexiaAI.AppImage && ./AlexiaAI.AppImage
```

Stiahne appku do domovského priečinka, povolí jej spustenie a spustí ju. Pri prvom spustení sa Alexia AI
sama pridá do **ponuky aplikácií**. Do Steamu: *Steam → Pridať hru → Pridať hru mimo Steamu → Alexia AI*
(bez Protonu – je to linuxová appka). AppImage sa pri aktualizácii vymení sám.

Ak by appka nešla spustiť, chyba je v `~/.local/share/AlexiaAI/posledne-spustenie.log`.

## Inštalácia na Windows

Stiahni [AlexiaAI-Windows.zip](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI-Windows.zip), rozbaľ ho
a spusti `AlexiaAI.exe`. Dáta (modely, chat, obrázky) sú v `%LOCALAPPDATA%\AlexiaAI`.
Pri aktualizácii rozbaľ nový ZIP cez starý priečinok – nič sa nestratí.
Ak Windows pri prvom spustení zobrazí *Windows ochránil váš počítač*, klikni *Ďalšie informácie → Spustiť aj tak* (appka nie je podpísaná certifikátom).

## Inštalácia na Android

Stiahni [AlexiaAI.apk](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk) a otvor ho.
Nové verzie appka ponúkne sama (APK stiahne prehliadač). Do mobilu sa hodia modely 0.5B–4B v kvantizácii Q4_0 alebo Q4_K_M –
väčšie modely použi cez *Prepojenie zariadení*.
