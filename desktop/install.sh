#!/usr/bin/env bash
# Installs, for this user, the Raspberry desktop app (a WebKit window on the web app) and the top-bar
# extension. GNOME on Wayland only picks up a new extension at login, so log out and back in afterwards.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
data="${XDG_DATA_HOME:-$HOME/.local/share}"
uuid=raspberry@kzhovn

install -Dm755 "$here/raspberry-app.js" "$data/raspberry-app/raspberry-app.js"
install -Dm644 "$here/raspberry-512.png" "$data/icons/hicolor/512x512/apps/com.kzhovn.Raspberry.png"
# The file name matches the app's id, which is how the dock pairs the window with this icon.
install -Dm644 /dev/stdin "$data/applications/com.kzhovn.Raspberry.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Raspberry
Comment=Your todo list
Exec=gjs -m $data/raspberry-app/raspberry-app.js %U
Icon=com.kzhovn.Raspberry
Categories=Office;
StartupNotify=true
DESKTOP

mkdir -p "$data/gnome-shell/extensions/$uuid"
cp "$here/$uuid"/* "$data/gnome-shell/extensions/$uuid/"

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
