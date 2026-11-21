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

✅ = mobil (Android) · 🖥 = Linux / SteamOS a Windows

**💬 Chat**
- ✅🖥 Akýkoľvek model **GGUF** (Qwen, Llama, Gemma, Mistral…) – z katalógu jedným tlačidlom, z odkazu Hugging Face (stačí odkaz na súbor alebo na celý repozitár) alebo zo súboru
- 🖥 Katalóg 12 overených modelov od 1,5B po 14B s označením, čo sa zmestí do pamäte
- ✅🖥 Odpovede sa píšu postupne, premýšľanie modelov (Qwen3) sa dá rozbaliť
- ✅🖥 **Kód** v tmavom okne ako vo VS Code s tlačidlom *Kopírovať*
- ✅🖥 **Jazyk odpovedí** – automaticky, slovensky alebo anglicky
- ✅🖥 **Hlas** – odpovede nahlas (PC/Linux: ženský slovenský hlas *Lili*, offline; mobil: slovenský hlas telefónu)
- ✅🖥 **História chatov** – viac rozhovorov, otvorenie a mazanie, všetko **šifrované AES-256** (mobil: kľúč v Android Keystore; PC: kľúč len pre teba + voliteľné **heslo** pri spustení)
- ✅🖥 Vlastný obrázok ako **pozadie chatu** so stmavením
- ✅🖥 Žiadny skrytý systémový prompt – správanie určuje model a tvoje nastavenia

**🎨 Obrázky**
- 🖥 Z-Image Turbo, FLUX.1 schnell, SD 1.5 (sťahujú sa jedným tlačidlom) · ✅ Stable Diffusion 1.5 v mobile
- ✅🖥 **Pozadia** na výber – príroda, pláž, hory, mesto, noc, sci-fi, cyberpunk, fantasy, vesmír, pod vodou, náhodné… alebo vlastné
- ✅🖥 Obrázky **priamo v chate** – „nakresli …“ alebo ikonka štetca
- 🖥 Každý obrázok má **seed** (napr. `Seed 12345`) – appka si ho pamätá
- 🖥 **Úprava obrázka v chate**: `uprav seed 12345 a pridaj šiltovku` – drží sa predlohy a zmení, čo napíšeš
  (`jemne` = drží sa predlohy viac, `výrazne` = viac zmien; ťuknutím na seed sa príkaz skopíruje).
  Funguje len pre obrázky vytvorené v appke, nie pre fotky zvonka.
- 🖥 `nakresli seed 12345 mačku` – obrázok s vlastným seedom

**🎬 Video**
- 🖥 **Krátke video v chate** – „sprav video: …“ alebo ikonka filmu: appka nakreslí 7 záberov a poskladá 18 s video na výšku 1080×1920
  s pohybom kamery, prechodmi (atrament, záblesk, glitch), iskrami a hudbou – prehrá sa priamo v chate, uloží ako MP4
- 🖥 **Vylepšenie hocijakého videa** (ako CapCut): doostrenie, odšumenie, živšie farby, stabilizácia, plynulých 60 fps,
  vylepšenie zvuku a **AI zväčšenie** na Full HD alebo 4K snímka po snímke. Obsah videa sa nemení.
- Video AI (postava, ktorá tancuje) appka nemá – na Legion Go S / PC by bola príliš pomalá a slabá

**✨ Vylepšenie fotky**
- ✅🖥 AI zväčšenie (Real-ESRGAN) 2×, 4×, 4K · 🖥 aj **8K** – obsah fotky sa nemení
- ✅🖥 Automatický jas, živšie farby, doostrenie

**🔗 Prepojenie zariadení**
- 🖥 *Nastavenia → Zdieľať môj model v sieti* – ukáže adresu a PIN (8 číslic, ochrana pred hádaním)
- ✅🖥 *Použiť model z iného zariadenia* – *Nájsť v sieti* alebo adresa + PIN; mobil potom chatuje s veľkým modelom (napr. 14B) a kreslí obrázky na Legion Go S / PC
- Cez internet: [Tailscale](https://tailscale.com) (zadarmo) na obe zariadenia a adresa `100.x.x.x`

**🎮 Ovládanie (PC / Legion Go S)**
- Záložky *Chat · Obrázky · Vylepšiť · Modely · Nastavenia* – **L1/R1** alebo **L2/R2**, posúvanie páčkou, **B** = späť
- Klávesnica: Ctrl+Tab, F1–F5, PageUp/PageDown, Esc
- Pole na písanie sa posunie nad klávesnicu na obrazovke (Steam), voliteľne ju appka otvorí sama
- Vlastné okno na výber súborov (funguje aj v hernom režime SteamOS)

**🔄 Aktualizácie** – ✅🖥 appka ponúkne novú verziu sama alebo *Nastavenia → Skontrolovať a stiahnuť aktualizácie*; AppImage sa vymení sám.

**🔒 Súkromie** – všetko beží offline v tvojom zariadení. Internet treba len na stiahnutie modelov, hlasu, ffmpeg a aktualizácií.
Mobil nemá prístup k tvojim súborom – fotky vyberáš cez systémový výber.

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
