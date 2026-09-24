# Deploying the server + bot to the GCP VM

One process serves `/sync` over HTTPS on port 8443 and runs the Discord bot.
Replace `<IP>`, `<VM>`, `<ZONE>` and `<REGION>` throughout. The `gcloud` steps
work from Cloud Shell or anywhere with the SDK installed.

## 1. Discord bot

1. Open https://discord.com/developers/applications, then **New Application**,
   then **Bot**. Copy the token; this is `BOT_TOKEN`.
2. On the same page, enable **Message Content Intent**.
3. Invite the bot. Go to **OAuth2 → URL Generator**, choose scope `bot` with
   the permissions *Send Messages*, *Read Message History* and
   *Add Reactions*, then open the generated URL.
4. In Discord, go to **Settings → Advanced** and enable **Developer Mode**.
   Right-click yourself and choose **Copy User ID** (this is
   `ALLOWED_USER_IDS`; list several ids comma-separated to let others use the bot. They all share
   one task list). Right-click the digest channel and choose
   **Copy Channel ID** (this is `DIGEST_CHANNEL_ID`). The digest channel must
   be a server channel, not a DM.

## 2. Static IP + firewall

```bash
gcloud compute addresses create todo-ip --region <REGION>
gcloud compute addresses describe todo-ip --region <REGION> --format='value(address)'   # -> <IP>
gcloud compute instances delete-access-config <VM> --zone <ZONE> --access-config-name "external-nat"
gcloud compute instances add-access-config <VM> --zone <ZONE> --access-config-name "external-nat" --address <IP>
gcloud compute firewall-rules create todo-sync --allow tcp:8443 --target-tags todo-sync
gcloud compute instances add-tags <VM> --zone <ZONE> --tags todo-sync
```

The access-config name may differ on your VM. Check it with
`gcloud compute instances describe <VM> --zone <ZONE>`.

## 3. Install on the VM

On your machine, build the server and copy it over:

```bash
./gradlew :server:installDist
gcloud compute scp --recurse server/build/install/server <VM>:/tmp/server --zone <ZONE>
gcloud compute scp server/deploy/todo-server.service <VM>:/tmp/ --zone <ZONE>
```

On the VM:

```bash
sudo apt-get install -y openjdk-17-jre-headless sqlite3
sudo useradd --system --home /var/lib/todo --create-home todo
sudo mkdir -p /opt/todo && sudo rm -rf /opt/todo/server && sudo mv /tmp/server /opt/todo/server

# Self-signed certificate for the static IP, valid 10 years. The app pins it.
sudo -u todo keytool -genkeypair -alias todo -keyalg RSA -keysize 2048 -validity 3650 \
  -dname CN=todo -ext san=ip:<IP> -storetype PKCS12 \
  -keystore /var/lib/todo/keystore.p12 -storepass '<KEYSTORE_PASSWORD>'
sudo -u todo keytool -exportcert -rfc -alias todo -keystore /var/lib/todo/keystore.p12 \
  -storepass '<KEYSTORE_PASSWORD>' > server_cert.pem
openssl rand -base64 32    # -> API_TOKEN
```

Create `/etc/todo-server.env` with `sudo chmod 600` (the service reads it as
root before dropping to `todo`):

```
API_TOKEN=...
BOT_TOKEN=...
ALLOWED_USER_IDS=...
DIGEST_CHANNEL_ID=...
DIGEST_TIME=08:00
DB_PATH=/var/lib/todo/todo.db
KEYSTORE_PATH=/var/lib/todo/keystore.p12
KEYSTORE_PASSWORD=...
PORT=8443
JAVA_OPTS=-Duser.timezone=America/New_York
```

`user.timezone` must be your zone. Recurrence, "today" and the digest time
all use it.

Then retire rusabot however it runs now (systemd, tmux, cron and so on), so
two bots don't both claim `--`, and start this one:

```bash
sudo mv /tmp/todo-server.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now todo-server
journalctl -u todo-server -f
```

## 4. Backups

Add a nightly database backup kept for 30 days, using `sudo crontab -e -u todo`:

```
0 3 * * * mkdir -p /var/lib/todo/backups && sqlite3 /var/lib/todo/todo.db ".backup /var/lib/todo/backups/todo-$(date +\%F).db" && find /var/lib/todo/backups -mtime +30 -delete
```

Those backups live on the VM's own disk, so they don't survive losing the VM.
For that, add a GCP snapshot schedule on the boot disk (Compute Engine →
Snapshots). Also copy `keystore.p12` and its password somewhere safe once. If
the keystore is lost, the app needs a rebuild with a new certificate.

## 5. Point the app at it

1. Copy `server_cert.pem` from the VM to `app/src/main/assets/server_cert.pem`
   in this repo, then rebuild and install the app. The certificate is public;
   committing it is fine.
2. In the app, open **Menu → Sync**, set the URL to `https://<IP>:8443`, enter
   the `API_TOKEN`, and tap **Save and sync now**.

## Updating later

Rebuild, `scp` the new `server/build/install/server` over `/opt/todo/server`,
then run `sudo systemctl restart todo-server`. The database and keystore live
in `/var/lib/todo` and aren't touched.
