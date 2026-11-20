#!/bin/bash
# Alexia AI – spustenie (SteamOS / Linux)
cd "$(dirname "$(readlink -f "$0")")"
# Steam (herný režim) pridáva vlastné knižnice a overlay – Jave a Vulkanu to prekáža, preto ich vypneme
unset LD_PRELOAD LD_LIBRARY_PATH
export _JAVA_AWT_WM_NONREPARENTING=1   # gamescope / herný režim: inak je okno prázdne alebo padne
LOG="${XDG_DATA_HOME:-$HOME/.local/share}/AlexiaAI"
mkdir -p "$LOG"
export JDK_JAVA_OPTIONS="-XX:ErrorFile=$LOG/java-chyba-%p.log"
exec ./AlexiaAI/bin/AlexiaAI "$@" >"$LOG/posledne-spustenie.log" 2>&1
