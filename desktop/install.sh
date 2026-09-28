#!/usr/bin/env bash
# Installs the Raspberry top-bar extension for this user. GNOME on Wayland only picks up a new
# extension at login, so log out and back in afterwards.
set -euo pipefail
uuid=raspberry@kzhovn
dest="${XDG_DATA_HOME:-$HOME/.local/share}/gnome-shell/extensions/$uuid"
mkdir -p "$dest"
cp "$(dirname "$0")/$uuid"/* "$dest/"

# Turned on for the next login (gnome-extensions enable only knows extensions already loaded).
current=$(gsettings get org.gnome.shell enabled-extensions)
case "$current" in
  *"'$uuid'"*) ;;
  "@as []") gsettings set org.gnome.shell enabled-extensions "['$uuid']" ;;
  *) gsettings set org.gnome.shell enabled-extensions "${current%]}, '$uuid']" ;;
esac

config="${XDG_CONFIG_HOME:-$HOME/.config}/raspberry-tray/config.json"
if [ ! -f "$config" ]; then
  mkdir -p "$(dirname "$config")"
  (umask 077; printf '{\n  "url": "https://34.69.150.11",\n  "token": "PASTE_API_TOKEN_HERE"\n}\n' > "$config")
  echo "Now put the API token (the one the phone syncs with) into $config"
fi
echo "Installed. Log out and back in to load it."
