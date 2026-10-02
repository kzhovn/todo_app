# Deploying the server

One process does it all: phone sync (`/sync`), the top bar's API (`/api/*`), the web app and the
Discord bot. It listens only on localhost. [Caddy](https://caddyserver.com) sits in front of it: it
gets a free Let's Encrypt certificate (for a domain, or even a bare IP address) and puts a login on
the web app. Sync and the top bar carry the API token instead, so they skip that login.

The steps are for a Debian or Ubuntu VM on Google Cloud; anywhere else only the firewall step
differs. Replace `<IP>`, `<VM>`, `<ZONE>` and `<REGION>` throughout. The `gcloud` steps work from
Cloud Shell or anywhere with the SDK installed.

## 1. Discord bot (optional)

1. Open https://discord.com/developers/applications, then **New Application**, then **Bot**. Copy
   the token; this is `BOT_TOKEN`.
2. On the same page, enable **Message Content Intent**.
3. Invite the bot. Go to **OAuth2 → URL Generator**, choose scope `bot` with the permissions *Send
   Messages*, *Read Message History* and *Add Reactions*, then open the generated URL.
4. In Discord, go to **Settings → Advanced** and enable **Developer Mode**. Right-click yourself and
   choose **Copy User ID**: this is `ALLOWED_USER_IDS` (several, comma-separated, share the one task
   list). Right-click the digest channel and choose **Copy Channel ID**: this is
   `DIGEST_CHANNEL_ID`. The digest channel must be a server channel, not a DM.

## 2. Static IP and firewall

```bash
gcloud compute addresses create todo-ip --region <REGION>
gcloud compute addresses describe todo-ip --region <REGION> --format='value(address)'   # -> <IP>
gcloud compute instances delete-access-config <VM> --zone <ZONE> --access-config-name "external-nat"
gcloud compute instances add-access-config <VM> --zone <ZONE> --access-config-name "external-nat" --address <IP>
gcloud compute firewall-rules create todo-web --allow tcp:80,tcp:443 --target-tags todo-web
gcloud compute instances add-tags <VM> --zone <ZONE> --tags todo-web
```

The access-config name may differ on your VM; check it with
`gcloud compute instances describe <VM> --zone <ZONE>`. Only 80 and 443 are open: the server itself
is never reachable from outside, only through Caddy.

## 3. Install the server

On your machine:

```bash
./gradlew :server:distTar
gcloud compute scp server/build/distributions/server.tar server/deploy/todo-server.service <VM>:/tmp/ --zone <ZONE>
```

(Or upload the two files from the browser SSH window's **Upload file** button; they land in your
home directory instead of `/tmp`.)

On the VM:

```bash
sudo apt-get install -y openjdk-17-jre-headless sqlite3
sudo useradd --system --home /var/lib/todo --create-home todo
sudo mkdir -p /opt/todo && sudo tar xf /tmp/server.tar -C /opt/todo
openssl rand -hex 32    # -> API_TOKEN
```

Create `/etc/todo-server.env` and `sudo chmod 600` it (systemd reads it as root before starting the
server as `todo`, so the tokens stay unreadable to everyone else):

```
API_TOKEN=...
HOST=127.0.0.1
PORT=8080
DB_PATH=/var/lib/todo/todo.db
JAVA_OPTS=-Duser.timezone=America/New_York
BOT_TOKEN=...
ALLOWED_USER_IDS=...
DIGEST_CHANNEL_ID=...
DIGEST_TIME=08:00
```

- `HOST=127.0.0.1` matters: the web app is only served on localhost (it has no login of its own;
  Caddy's is it). Listening anywhere else, the server serves only `/sync` and `/api/*`.
- `user.timezone` must be your time zone: recurrence, "today" and the digest time use it.
- Leave out the four bot lines to run without the Discord bot, or the two digest lines to skip the
  morning digest and the nudges that come with it. To stop just the digest, turn it off in Settings
  (web or phone) instead.

Start it:

```bash
sudo mv /tmp/todo-server.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now todo-server
journalctl -u todo-server -f
```

## 4. Caddy

Install Caddy from its own apt repository (see https://caddyserver.com/docs/install#debian-ubuntu-raspbian;
Debian's package is too old for IP certificates). Make a password hash for the web app's login:

```bash
caddy hash-password    # asks for the password, prints the hash
```

Then write `/etc/caddy/Caddyfile`. With a bare IP address:

```
{
	# Browsers send no server name for an IP address; this tells Caddy which certificate to use.
	default_sni <IP>
}

<IP> {
	# Let's Encrypt only issues IP-address certificates on its short-lived profile (renewed automatically).
	tls {
		issuer acme {
			profile shortlived
		}
	}
	# The web app's login; sync and the top bar's API carry the API token instead.
	@web not path /sync /api/*
	basic_auth @web {
		<USER> <HASH>
	}
	reverse_proxy 127.0.0.1:8080
}
```

With a domain pointing at the VM, drop the global block and the `tls` block and use the domain in
place of `<IP>`. Then:

```bash
sudo systemctl reload caddy
```

Open `https://<IP>/` in a browser: it asks for the login, then shows the web app.

## 5. Connect the devices

- **Phone:** in the app, open **Settings** from the side menu, set the server URL to `https://<IP>`,
  enter the `API_TOKEN`, and sync.
- **Desktop (GNOME):** run `./desktop/install.sh` on the computer, put the URL and `API_TOKEN` in
  `~/.config/raspberry-tray/config.json`, then log out and back in.
- **The day's rollover hour** (when "today only" tasks go) is set from the phone's Settings or the
  web app's Settings page; the newest setting wins everywhere.

## 6. Backups

Add a nightly database backup kept for 30 days, with `sudo crontab -e -u todo`:

```
0 3 * * * mkdir -p /var/lib/todo/backups && sqlite3 /var/lib/todo/todo.db ".backup /var/lib/todo/backups/todo-$(date +\%F).db" && find /var/lib/todo/backups -mtime +30 -delete
```

Those live on the VM's own disk, so they don't survive losing the VM. For that, add a snapshot
schedule on the boot disk (Compute Engine → Snapshots), or copy the backups to a Cloud Storage
bucket. The phone also keeps a full copy of the list.

## 7. Hands-free adds through Google Tasks (optional)

Say "Hey Google, add call mom due Friday to my tasks" (in the car, on the phone, anywhere) and it
lands in Raspberry within a minute. Google's assistant puts it in Google Tasks; the server checks
your default Tasks list every minute, adds each item through quick add (so "due Friday" and the rest
work), and deletes it from Google Tasks. The list is only an inbox: everything on it is taken. A plain
add is starred, so it shows up in Doing.

It needs a one-time Google sign-in, which gives the server a lasting key to your Tasks:

1. In the [Google Cloud console](https://console.cloud.google.com), in the VM's project, open
   **APIs & Services → Library**, find **Google Tasks API** and enable it.
2. Open **Google Auth Platform** (the OAuth consent screen). Set it up with any app name (say
   "Raspberry") and your email, audience **External**. Then, under **Audience**, press **Publish app**
   so it's **In production**: while it's in testing, Google's sign-in expires after 7 days.
3. Under **Clients**, create a client of type **Desktop app**. Keep its client ID and secret.
4. On your computer, run `python3 tools/google_tasks_auth.py`, enter the ID and secret, and sign in
   in the browser that opens. Google warns that it hasn't verified the app (it's your own): choose
   **Advanced**, then go on, and allow access to your tasks.
5. It prints three lines. Add them to `/etc/todo-server.env`, with the real secret, and restart:

   ```
   GOOGLE_CLIENT_ID=...
   GOOGLE_CLIENT_SECRET=...
   GOOGLE_REFRESH_TOKEN=...
   ```

   The journal then says `Google Tasks import on`, and `Google Tasks: added 1` after each add.

Try it: "Hey Google, add call mom to my tasks". If your assistant puts it somewhere else (Keep, a
reminder), say "…to my Google Tasks" instead.

## Updating

Build a new `server.tar`, copy it over as before, then on the VM:

```bash
sudo systemctl stop todo-server && sudo tar xf /tmp/server.tar -C /opt/todo && sudo systemctl start todo-server
```

The database lives in `/var/lib/todo`, so updating doesn't touch it.
