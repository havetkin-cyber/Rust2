# Alexia AI

Lokálny AI chat, hlas, tvorba obrázkov a videí – beží **priamo v tvojom zariadení, offline** (llama.cpp, stable-diffusion.cpp, sherpa-onnx).
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

Všetky tri verzie (mobil, Windows, Linux) majú vždy **rovnaké číslo verzie** – vydávajú sa spolu.
Všetky obrazovky sú **rozbaľovacie** ako otázky a odpovede: vidno len nadpisy, ťuknutím sa otvorí len tá časť.

**💬 Chat**
- ✅🖥 Akýkoľvek model **GGUF** (Qwen, Llama, Gemma, Mistral…) – z katalógu jedným tlačidlom, z odkazu Hugging Face alebo zo súboru
- 🖥 Katalóg overených modelov od 1,5B po 14B s označením, čo sa zmestí do pamäte
- ✅🖥 Odpovede sa píšu postupne, premýšľanie modelov (Qwen3) sa dá rozbaliť; **kód** s tlačidlom *Kopírovať*
- ✅🖥 **Chat so súbormi** – PDF, knihy, texty; appka nájde súvisiace úryvky a odpovedá podľa nich
- ✅🖥 **Diktovanie** cez Whisper (offline)
- ✅🖥 **História chatov** šifrovaná AES-256, vlastné **pozadie chatu**, jazyk odpovedí (automaticky / SK / EN)

**🗣 Hlas**
- ✅🖥 Ženské hlasy **Lili** a **Ema, Nina, Sofia, Zara, Hana** (Supertonic) + **Mix** dvoch hlasov, všetko offline
- ✅🖥 Úprava **rýchlosti, výšky, hrúbky a plnosti** hlasu
- ✅🖥 Odpovede nahlas a **čítanie kníh** nahlas zvoleným hlasom (pamätá si, kde si skončil)
- ✅🖥 **Nahovoriť text do súboru** – napíšeš text, hlas ho povie a uloží ako **WAV** (✅) alebo **WAV/MP3** (🖥)

**🎨 Obrázky**
- 🖥 **FLUX.3 Image Klein**, FLUX.1 schnell, Z-Image Turbo, SD 1.5 · ✅ SD 1.5 v mobile, ✅ **všetky modely PC cez tlačidlo „Na PC“**
- ✅🖥 **AI napíše prompt** – napíšeš krátky nápad aj po slovensky, chatový model z neho spraví presný anglický prompt
- ✅🖥 **Tvar obrázka** – štvorec, na výšku, na šírku, náhodne alebo vlastný rozmer (šírka × výška)
- ✅🖥 Pozadia na výber, 1 / 4 / 8 variantov naraz, obrázky aj priamo v chate
- 🖥 Každý obrázok má **seed**; úprava obrázka podľa seedu (`uprav seed 12345 a pridaj šiltovku`)
- ✅🖥 **Prenos štýlu** (FLUX.3 Klein) – obrázok z Alexia AI prekreslí v štýle iného obrázka (farby, svetlo, technika); v mobile cez PC
- ✅🖥 **Úprava v chate podľa seedu** – napíš `/úprava seed 8234 pridaj čiapku` (aj `uprav 8234 …`, `zmeň seed 8234 …`, `/edit 8234 add a hat`).
  Chatový model preloží pokyn do angličtiny; s FLUX.3 Klein sa zmení len to, čo chceš (zvyšok obrázka ostane), so Z-Image / FLUX.1 / SD
  sa obrázok prekreslí s rovnakým seedom. V mobile to robí pripojené PC (obrázok z galérie PC). Len obrázky vytvorené v Alexia AI.
- ✅🖥 **Spojenie dvoch postáv v chate** – `/spoj seed 8234 a seed 5120: stoja spolu na ulici v meste` → postavy z dvoch obrázkov appky
  spolu na jednom obrázku (FLUX.3 Klein – tvár, vlasy a oblečenie ostanú; so Z-Image / FLUX.1 / SD sa postavy položia vedľa seba
  a model ich prekreslí do jednej scény). Windows aj Linux; v mobile cez PC. Len obrázky z Alexia AI.
- ✅🖥 **Sekcia Prompt** – model, ktorý vidí obrázky (Qwen3-VL 2B, ~1,8 GB, stiahne sa pri prvom použití), napíše z hocijakého obrázka –
  aj bežnej fotky – veľmi podrobný anglický prompt (štýl, postava, tvár, vlasy, oblečenie, póza, prostredie, svetlo, kamera, farby),
  alebo len opis štýlu. Beží na procesore, skutočných ľudí nepomenúva, explicitný obsah nespracuje. V mobile v Obrázkoch cez PC.
- ✅🖥 **Prenos štýlu bez písania** – model videnia sám opíše štýl predlohy a pridá ho ku Kleinu (prepínač *Automatický opis štýlu*).
- ✅🖥 **Stopky** – pri tvorbe obrázka, videa aj vylepšenia beží čas od 0:00, po dokončení ukáže *Hotovo za m:ss*
- ✅🖥 **Údaje pod obrázkom** – prompt (tlačidlo *Kopírovať prompt*), seed, čas tvorby, rozmer a model sa uložia k obrázku a ukážu sa v detaile
- Upravovať (seed, prenos štýlu, rozhýbanie) sa dajú **len obrázky vytvorené v appke**, nie fotky zvonka

**🎬 Videá**
- 🖥 **Wan 2.2** – video z textu, aj **dlhé 1–3 minúty** (skladá sa z nadväzujúcich kúskov), 60 fps, zväčšenie na Full HD / 4K pri tvorbe
- 🖥 **Najprv obrázok** – FLUX nakreslí presný prvý snímok, Wan ho rozhýbe
- 🖥 **Podľa obrázka** – napíšeš len **seed** obrázka z galérie alebo **popis postavy**, hlavný popis je potom len pohyb
- 🖥 Rozhýbanie obrázka z galérie, skladanie videa VAE / TAE / LightTAE (rýchlejšie, menej VRAM), Šetriť VRAM
- ✅ **Videá PC** – z mobilu pošleš video na PC, PC ho spraví aj keď pri ňom nie si (rad úloh), hotové stiahneš do mobilu;
  rozhýbanie obrázkov z PC a galéria PC. **Zamknuté, kým mobil nie je pripojený k PC.** Pri každej úlohe beží čas, hotová ukáže, ako dlho trvala.
- 🖥 Ku každému videu sa uloží prompt, dĺžka, model a čas tvorby (`video.mp4.json`)
- 🖥 Oprava pádu pri tvorbe videa na notebookoch s dvomi grafikami – integrovaná Intel/AMD grafika sa pri tvorbe nepoužíva, všetko ide na NVIDIA
- 🖥 **Video po kúskoch** (zapnuté) – aj 3–5 s videá sa robia po 2 s nadväzujúcich kúskoch; na 6 GB grafikách to zabráni pádu „device lost“ (Windows inak reštartuje ovládač grafiky)
- ✅🖥 **Videoklip na skladbu** – vyberieš skladbu a obrázky vytvorené v Alexia AI; appka nájde tempo a údery, obrázky strieda do rytmu
  (v refréne rýchlejšie), každý záber sa jemne približuje / posúva, pod to dá pôvodnú skladbu. Strih *pomaly / stredne / rýchlo*,
  rozmer podľa prvého obrázka (na výšku 1080×1920, na šírku 1920×1080, štvorec). Ide na procesore, grafiku nepotrebuje.
  ✅ V mobile cez **Videá PC**: skladba z mobilu, obrázky z galérie PC, hotový klip si stiahneš do mobilu.
- 🖥 **Postava spieva** – postava z obrázka spieva skladbu: pery sa hýbu podľa spevu (Wav2Lip GAN), hrá pôvodná nahrávka,
  kamera sa jemne hýbe. Najlepšie tvár zblízka; len obrázky z Alexia AI. Ide na procesore v samostatnom procese,
  model (~160 MB) sa stiahne pri prvom použití. Wav2Lip je len na **osobné, nekomerčné** použitie. ✅ Aj z mobilu cez **Videá PC**.
- 🖥 **Vylepšenie hocijakého videa** – doostrenie, odšumenie, farby, stabilizácia, 60 fps, AI zväčšenie na Full HD / 4K

**🧩 LoRA adaptéry** (🖥)
- Kategórie **Obrázky, Videá, Chat** – pridanie odkazom (Hugging Face, Civitai) alebo zo súboru, zapnutie a **sila**
- LoRA musí byť pre rovnaký model (FLUX.1, FLUX.3 Klein, Wan 2.2 5B, rovnaký GGUF chatový model)

**✨ Vylepšenie fotky**
- ✅🖥 AI zväčšenie (Real-ESRGAN) 2×, 4×, 4K · 🖥 aj **8K**; ✅ tlačidlá **V mobile / Na PC** (na PC oveľa rýchlejšie)
- ✅🖥 Automatický jas, živšie farby, doostrenie – obsah fotky sa nemení

**📁 Tvoje súbory (PC)** – všetko vytvorené je v **Dokumenty/Alexia AI**: `Rozhovory`, `Obrazky`, `Videa`, `Hlasy`, `Logy`, `LoRA`.
Modely a nastavenia ostávajú v priečinku appky (`%LOCALAPPDATA%\AlexiaAI` / `~/.local/share/AlexiaAI`).
Pri chybe: *Nastavenia → Aktualizácie → Otvoriť logy* (`alexia.log`, `sd.log`).

**🔗 Prepojenie zariadení**
- 🖥 *Nastavenia → Zdieľať môj model v sieti* – ukáže adresu a PIN (8 číslic, ochrana pred hádaním)
- ✅ *Prepojenie zariadení* – *Nájsť v sieti* (rovnaká Wi-Fi) alebo adresa + PIN; cez internet [Tailscale](https://tailscale.com) a adresa `100.x.x.x`
- ✅ Keď PC nemá spustený chatový model, chat beží v mobile a obrázky/videá cez PC fungujú ďalej

**🎮 Ovládanie (PC / Legion Go S)**
- Záložky *Chat · Obrázky · Videá · Vylepšiť · LoRA · Modely · Nastavenia* – **L1/R1** alebo **L2/R2**, posúvanie páčkou, **B** = späť
- Klávesnica: Ctrl+Tab, F1–F5, PageUp/PageDown, Esc; vlastné okno na výber súborov (aj v hernom režime SteamOS)

**🔄 Aktualizácie** – ✅🖥 **priamo v appke**: PC si nový balík stiahne a súbory vymení samo (aj pri zatvorení appky),
mobil stiahne APK a nainštaluje ho (prvýkrát povoliť „Inštalovať neznáme aplikácie“, od Androidu 12 potom bez potvrdzovania).

**🔒 Súkromie** – všetko beží offline v tvojom zariadení. Internet treba len na stiahnutie modelov, hlasov, ffmpeg a aktualizácií.
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
a spusti `AlexiaAI.exe`. Modely a nastavenia sú v `%LOCALAPPDATA%\AlexiaAI`, tvoje výtvory v `Dokumenty\Alexia AI`.
Ďalšie aktualizácie si appka stiahne a nainštaluje sama.
Ak Windows pri prvom spustení zobrazí *Windows ochránil váš počítač*, klikni *Ďalšie informácie → Spustiť aj tak* (appka nie je podpísaná certifikátom).

## Inštalácia na Android

Stiahni [AlexiaAI.apk](https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk) a otvor ho.
Nové verzie si appka stiahne a nainštaluje sama. Do mobilu sa hodia modely 0.5B–4B v kvantizácii Q4_0 alebo Q4_K_M –
väčšie modely použi cez *Prepojenie zariadení*.
