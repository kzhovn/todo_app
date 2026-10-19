#!/bin/bash
# One-time VM setup, run by Kira from the browser SSH window with todo-deploy and todo-server.service next to it:
#   bash setup-deploy.sh
# Moves the live server (~/server, unit `todo`, ~/todo.env, ~/todo_app/todo.db) to DEPLOY.md's layout (own
# `todo` user, /opt/todo, /var/lib/todo, unit todo-server, /etc/todo-server.env), adds a nightly backup, and a
# `deploy` user whose only key runs todo-deploy. Leaves the old files in place (renamed where they'd confuse).
#
# If it fails before the new server is up, it restarts the old one. To run it again after that, undo first:
#   sudo userdel -r todo; sudo userdel -r deploy; sudo rm -rf /opt/todo /etc/todo-server.env \
#     /etc/systemd/system/todo-server.service /etc/cron.d/todo-backup /etc/sudoers.d/todo-deploy
set -euo pipefail
cd "$(dirname "$0")"
PUBKEY='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINStSGhbW4bOKBauXWAIjY4SlEnf5S93/Rzn2lMx3riq claude-deploy'
OLD_HOME=$HOME
OLD_DB=$OLD_HOME/todo_app/todo.db
[ -f todo-deploy ] && [ -f todo-server.service ] || { echo "todo-deploy and todo-server.service must be next to this script"; exit 1; }
grep -qx "DB_PATH=$OLD_DB" "$OLD_HOME/todo.env" || { echo "todo.env's DB_PATH isn't $OLD_DB"; exit 1; }
[ -f "$OLD_DB" ] || { echo "no $OLD_DB"; exit 1; }
[ -d /opt/todo ] && { echo "/opt/todo already exists: setup has run before"; exit 1; }

echo "== packages"
sudo apt-get install -y -q sqlite3

# Until the new server is up, any failure goes back to the old one.
restore_old() {
    echo "!! setup failed: starting the old server again" >&2
    sudo systemctl disable --now todo-server 2>/dev/null || true
    [ -f "$OLD_DB.migrated" ] && [ ! -f "$OLD_DB" ] && mv "$OLD_DB.migrated" "$OLD_DB"
    sudo systemctl enable todo
    sudo systemctl start todo
}
trap restore_old ERR

echo "== stop the old server, back everything up"
sudo systemctl stop todo
keep=$OLD_HOME/todo-migration-$(date +%F)
mkdir -p "$keep"
sqlite3 "$OLD_DB" ".backup '$keep/todo.db'"
cp -a "$OLD_HOME/todo.env" "$keep/"
chmod 700 "$keep" && chmod 600 "$keep"/*
echo "copies in $keep"

echo "== users"
sudo useradd --system --home /var/lib/todo --create-home --shell /usr/sbin/nologin todo
sudo useradd --system --home /var/lib/todo-deploy --create-home --shell /bin/sh deploy
# Group access to the database (backups) and the journal (status).
sudo usermod -aG todo,systemd-journal deploy

echo "== data"
sudo chmod 2770 /var/lib/todo
sudo install -d -o todo -g todo -m 2770 /var/lib/todo/backups
sudo install -o todo -g todo -m 660 "$keep/todo.db" /var/lib/todo/todo.db
mv "$OLD_DB" "$OLD_DB.migrated"

echo "== code"
sudo install -d -o deploy -g deploy -m 755 /opt/todo
sudo cp -a "$OLD_HOME/server" /opt/todo/server
sudo chown -R deploy:deploy /opt/todo/server
sudo chmod -R a+rX /opt/todo/server

echo "== env"
# New DB_PATH; a key given twice keeps its last value, as systemd read it.
sed 's|^DB_PATH=.*|DB_PATH=/var/lib/todo/todo.db|' "$OLD_HOME/todo.env" \
    | tac | awk -F= '!/^[A-Za-z_]+=/ || !seen[$1]++' | tac \
    | sudo install -m 600 /dev/stdin /etc/todo-server.env
chmod 600 "$OLD_HOME/todo.env"

echo "== service"
sudo install -m 644 todo-server.service /etc/systemd/system/todo-server.service
sudo systemctl daemon-reload
sudo systemctl disable todo
sudo systemctl enable --now todo-server
for _ in $(seq 30); do curl -fs -o /dev/null http://127.0.0.1:8080/ && break; sleep 2; done
curl -fs -o /dev/null http://127.0.0.1:8080/
trap - ERR
echo "todo-server is up (from here on a failure leaves it running)"

echo "== nightly backup (30 days)"
echo '0 3 * * * todo sqlite3 /var/lib/todo/todo.db ".backup /var/lib/todo/backups/nightly-$(date +\%F).db" && find /var/lib/todo/backups -name "nightly-*" -mtime +30 -delete' \
    | sudo install -m 644 /dev/stdin /etc/cron.d/todo-backup

echo "== deploy key"
sudo install -m 755 -o root -g root todo-deploy /usr/local/bin/todo-deploy
sudo install -d -m 755 /etc/ssh/keys
echo "restrict,command=\"/usr/local/bin/todo-deploy\" $PUBKEY" | sudo install -m 644 /dev/stdin /etc/ssh/keys/deploy
# Checked before it goes live: a broken sudoers file would lock out sudo for everyone.
sudoers=$(mktemp)
echo 'deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart todo-server' >"$sudoers"
sudo visudo -cf "$sudoers"
sudo install -m 440 -o root -g root "$sudoers" /etc/sudoers.d/todo-deploy
rm -f "$sudoers"
# At the very end of sshd_config, so the Match block can't swallow later settings; the key file is root-owned,
# so nothing running as deploy can rewrite it.
sudo cp /etc/ssh/sshd_config "$keep/sshd_config"
printf '\nMatch User deploy\n\tAuthorizedKeysFile /etc/ssh/keys/%%u\n' | sudo tee -a /etc/ssh/sshd_config >/dev/null
if ! sudo sshd -t; then
    sudo cp "$keep/sshd_config" /etc/ssh/sshd_config
    echo "sshd config invalid; restored it. The server itself is fine." >&2
    exit 1
fi
sudo systemctl reload ssh

echo
echo "Done. The old server's files are still in $OLD_HOME (server/, todo_app/todo.db.migrated, todo.env);"
echo "delete them once everything has run fine for a few days. Copies: $keep"
