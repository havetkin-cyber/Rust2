#!/bin/bash
# Alexia AI – spustenie (SteamOS / Linux)
cd "$(dirname "$(readlink -f "$0")")"
exec ./AlexiaAI/bin/AlexiaAI "$@"
