#!/bin/bash
# Pridá Alexia AI do ponuky aplikácií (KDE na SteamOS). Odtiaľ ju pridáš do Steamu cez "Add to Steam".
set -e
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
chmod +x "$DIR/spustit.sh" "$DIR/AlexiaAI/bin/AlexiaAI" 2>/dev/null || true
find "$DIR/AlexiaAI/lib/runtime/bin" -type f -exec chmod +x {} \; 2>/dev/null || true
mkdir -p "$HOME/.local/share/applications"
cat > "$HOME/.local/share/applications/alexia-ai.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Alexia AI
Comment=Lokálny AI chat a obrázky (offline)
Exec="$DIR/spustit.sh"
Icon=$DIR/ikona.png
Terminal=false
Categories=Utility;
DESKTOP
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
echo "Hotovo: Alexia AI je v ponuke aplikácií. V Steame: Add a Game > Add a Non-Steam Game > Alexia AI."
