#!/usr/bin/env -S gjs -m
// The Raspberry desktop app: the web app in a WebKit window of its own, with a dock icon and a
// launcher entry. It adds no UI; everything inside is the web app. One instance: opening a page
// again (the top-bar extension's links) shows it in the same window.
import GLib from 'gi://GLib';
import Gio from 'gi://Gio';
import Gtk from 'gi://Gtk?version=4.0';
import WebKit from 'gi://WebKit?version=6.0';
import System from 'system';

const APP_ID = 'com.kzhovn.Raspberry';
// Shared with the top-bar extension; only `url` is used here (the site's own login applies).
const CONFIG = GLib.build_filenamev([GLib.get_user_config_dir(), 'raspberry-tray', 'config.json']);

function siteUrl() {
    try {
        const [, bytes] = GLib.file_get_contents(CONFIG);
        return JSON.parse(new TextDecoder().decode(bytes)).url.replace(/\/$/, '');
    } catch {
        return null;
    }
}

// WebKit runs each page in a bubblewrap sandbox, which Ubuntu 24.04's AppArmor blocks for apps
// without a profile of their own (the page process can't start, and the app crashes). This window
// only ever shows your own site (other links open in the browser), so it runs without it.
GLib.setenv('WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS', '1', true);

const app = new Gtk.Application({application_id: APP_ID, flags: Gio.ApplicationFlags.HANDLES_OPEN});
const site = siteUrl();
let window, view;

function build() {
    const dir = (base) => GLib.build_filenamev([base, 'raspberry-app']);
    // Kept on disk, so the login, cookies and the web app's own storage (its timer) survive restarts.
    const session = new WebKit.NetworkSession({data_directory: dir(GLib.get_user_data_dir()), cache_directory: dir(GLib.get_user_cache_dir())});
    session.get_cookie_manager().set_persistent_storage(GLib.build_filenamev([dir(GLib.get_user_data_dir()), 'cookies.sqlite']), WebKit.CookiePersistentStorage.SQLITE);
    view = new WebKit.WebView({network_session: session});

    // The site's pages stay here; anything else (a Discord link, say) opens in the default browser.
    view.connect('decide-policy', (_view, decision, type) => {
        const NAV = WebKit.PolicyDecisionType;
        if (type !== NAV.NAVIGATION_ACTION && type !== NAV.NEW_WINDOW_ACTION) return false;
        const uri = decision.get_navigation_action().get_request().get_uri();
        if (uri === site || uri.startsWith(`${site}/`)) {
            if (type === NAV.NAVIGATION_ACTION) return false;
            decision.ignore();
            view.load_uri(uri);
            return true;
        }
        decision.ignore();
        Gio.AppInfo.launch_default_for_uri(uri, null);
        return true;
    });
    // WebKit's own dialog only pre-fills a saved login, so it would ask on every launch. The first
    // login goes through it (tick "Remember password" to keep it in the GNOME keyring); after that
    // the saved one is sent without asking, and the dialog comes back only if the server rejects it.
    view.connect('authenticate', (_view, request) => {
        const saved = request.get_proposed_credential();
        if (!saved?.has_password() || request.is_retry()) return false;
        request.authenticate(saved);
        return true;
    });
    // The web app's timer notifies when time's up.
    view.connect('permission-request', (_view, request) => {
        if (!(request instanceof WebKit.NotificationPermissionRequest)) return false;
        request.allow();
        return true;
    });

    window = new Gtk.ApplicationWindow({application: app, title: 'Raspberry', default_width: 1100, default_height: 800, child: view});
    view.connect('notify::title', () => {
        window.title = view.title || 'Raspberry';
    });
    const reload = new Gio.SimpleAction({name: 'reload'});
    reload.connect('activate', () => view.reload());
    window.add_action(reload);
    app.set_accels_for_action('win.reload', ['<Control>r', 'F5']);

    if (!site) view.load_html(`<p style="font: 15px system-ui; padding: 24px">Put the site's address ({"url": …}) in ${CONFIG}</p>`, null);
}

function show(url) {
    const first = !window;
    if (first) build();
    if (site && (url || first)) view.load_uri(url ?? `${site}/doing`);
    window.present();
}

app.connect('activate', () => show(null));
app.connect('open', (_app, files) => show(files[0].get_uri()));
app.run([System.programInvocationName, ...ARGV]);
