#!/usr/bin/env python3
"""One-time Google sign-in for the Google Tasks import (server/DEPLOY.md, "Hands-free adds").

Asks for the OAuth client's id and secret, opens Google's consent page in your browser, and prints
the refresh token to put in the server's environment as GOOGLE_REFRESH_TOKEN. Nothing is saved.
"""
import getpass
import http.server
import json
import secrets
import urllib.parse
import urllib.request
import webbrowser

SCOPE = "https://www.googleapis.com/auth/tasks"

client_id = input("OAuth client ID: ").strip()
client_secret = getpass.getpass("OAuth client secret (not shown): ").strip()
state = secrets.token_urlsafe(16)
result = {}


class Callback(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        if query.get("state") == [state]:
            result.update({k: v[0] for k, v in query.items()})
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write("Done: you can close this tab and go back to the terminal.".encode())

    def log_message(self, *args):
        pass


# Google sends the browser back here (a "Desktop app" client allows any localhost port).
server = http.server.HTTPServer(("127.0.0.1", 0), Callback)
redirect = f"http://127.0.0.1:{server.server_port}"
url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
    "client_id": client_id, "redirect_uri": redirect, "response_type": "code", "scope": SCOPE,
    "access_type": "offline", "prompt": "consent", "state": state,
})
print(f"\nOpening Google's sign-in page. If it doesn't open, visit:\n{url}\n")
webbrowser.open(url)
while "code" not in result and "error" not in result:
    server.handle_request()
if "error" in result:
    raise SystemExit(f"Google said: {result['error']}")

body = urllib.parse.urlencode({
    "code": result["code"], "client_id": client_id, "client_secret": client_secret,
    "redirect_uri": redirect, "grant_type": "authorization_code",
}).encode()
with urllib.request.urlopen("https://oauth2.googleapis.com/token", body) as response:
    token = json.load(response)
if "refresh_token" not in token:
    raise SystemExit("Google didn't return a refresh token; remove the app's access at myaccount.google.com/permissions and run this again.")
print("Add these to the server's environment file, then restart the server:\n")
print(f"GOOGLE_CLIENT_ID={client_id}")
print("GOOGLE_CLIENT_SECRET=<the secret you just entered>")
print(f"GOOGLE_REFRESH_TOKEN={token['refresh_token']}")
