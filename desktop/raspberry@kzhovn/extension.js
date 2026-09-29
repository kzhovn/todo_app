// The Raspberry tray: the phone widget's Doing/Active/All lists in a top-bar popup, with the current
// task (its pin, timer and any focus session, shared by every device) in the top bar. Rows come ready-made from the server's /api/tray (the widget's own rows), so
// nothing here decides what's in Doing or how a row reads. See
// docs/superpowers/specs/2026-09-27-desktop-tray-design.md.
import Clutter from 'gi://Clutter';
import GLib from 'gi://GLib';
import GObject from 'gi://GObject';
import Gio from 'gi://Gio';
import Pango from 'gi://Pango';
import Soup from 'gi://Soup?version=3.0';
import St from 'gi://St';

import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

Gio._promisify(Soup.Session.prototype, 'send_and_read_async');

const CONFIG = GLib.build_filenamev([GLib.get_user_config_dir(), 'raspberry-tray', 'config.json']);
const REFRESH_SECONDS = 60;
const TOP_BAR_CHARS = 40;
const PARENT_CHARS = 22; // like the widget: a long parent can't crowd out the title

const hex = (argb) => (argb == null ? null : `#${(argb & 0xffffff).toString(16).padStart(6, '0')}`);
const cut = (text, max) => (text.length > max ? `${text.slice(0, max - 1)}…` : text);
const clock = (date) => date.toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'});
// A timer's time left, "18:42" or "1:05:00", and a task's length, "45m" / "1h 30m", as on the phone.
const countdown = (ms) => {
    const s = Math.max(0, Math.ceil(ms / 1000)), mm = String(Math.floor(s / 60) % 60).padStart(2, '0'), ss = String(s % 60).padStart(2, '0');
    return s >= 3600 ? `${Math.floor(s / 3600)}:${mm}:${ss}` : `${Math.floor(s / 60)}:${ss}`;
};
// Quick add's Start and Due chips: quick add's own words, sent as typed.
const DAY_LABELS = {today: 'Today', tomorrow: 'Tomorrow', 'next week': 'Next week', weekend: 'Weekend'};
const duration = (m) => (m < 60 ? `${m}m` : m % 60 === 0 ? `${m / 60}h` : `${Math.floor(m / 60)}h ${m % 60}m`);

// The app's own Material icons (icons/, made by tools/tray_icons.py from the web's), so the top bar
// looks like the phone and web rather than the desktop theme.
let iconDir = null;
const appIcon = (name) => Gio.icon_new_for_string(`${iconDir}/${name}.svg`);

function readConfig() {
    try {
        const [, bytes] = GLib.file_get_contents(CONFIG);
        const {url, token} = JSON.parse(new TextDecoder().decode(bytes));
        return url && token ? {url: url.replace(/\/$/, ''), token} : null;
    } catch {
        return null;
    }
}

class Api {
    constructor({url, token}) {
        this.url = url;
        this._token = token;
        this._session = new Soup.Session({timeout: 15});
    }

    async call(method, path, form = null) {
        const message = Soup.Message.new(method, this.url + path);
        message.request_headers.append('Authorization', `Bearer ${this._token}`);
        if (form) {
            const body = Object.entries(form).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
            message.set_request_body_from_bytes('application/x-www-form-urlencoded', new GLib.Bytes(new TextEncoder().encode(body)));
        }
        const bytes = await this._session.send_and_read_async(message, GLib.PRIORITY_DEFAULT, null);
        if (message.get_status() !== Soup.Status.OK) throw new Error(`HTTP ${message.get_status()}`);
        return JSON.parse(new TextDecoder().decode(bytes.get_data()));
    }

    abort() {
        this._session.abort();
    }
}

// Opens a page of the web app in the Raspberry desktop app (desktop/raspberry-app.js) when it's
// installed, else in the default browser.
function openPage(url) {
    const context = global.create_app_launch_context(0, -1);
    const app = Gio.DesktopAppInfo.new('com.kzhovn.Raspberry.desktop');
    if (app) app.launch_uris([url], context);
    else Gio.AppInfo.launch_default_for_uri(url, context);
}

const Indicator = GObject.registerClass(
class Indicator extends PanelMenu.Button {
    _init(extension) {
        super._init(0.5, 'Raspberry');
        iconDir = `${extension.path}/icons`;
        this._state = null;
        this._mode = 'doing';
        this._expanded = new Set(); // rows whose subtasks/items are shown in place, like the widget's
        this._status = '';
        this._draft = ''; // quick add's text and chips, kept across re-renders
        this._chips = {};

        const bar = new St.BoxLayout({style_class: 'panel-status-menu-box'});
        bar.add_child(new St.Icon({gicon: Gio.icon_new_for_string(`${extension.path}/raspberry.png`), style_class: 'rb-panel-icon'}));
        // The pinned task's title; with nothing pinned, just the icon.
        this._pinLabel = new St.Label({style_class: 'rb-panel-label', y_align: Clutter.ActorAlign.CENTER, visible: false});
        bar.add_child(this._pinLabel);
        this.add_child(bar);

        this._popup = new St.BoxLayout({style_class: 'rb-popup', vertical: true});
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, can_focus: false, style_class: 'rb-item'});
        item.add_child(this._popup);
        this.menu.addMenuItem(item);
        // The popup is only built while it's open: this all runs inside GNOME Shell, so nothing is
        // drawn that isn't on screen. Closed, a refresh just keeps the top bar's pinned title current.
        this.menu.connect('open-state-changed', (_menu, open) => {
            if (open) {
                this._render();
                this._refresh();
            } else {
                this._countdownLabel = null;
                this._popup.destroy_all_children();
            }
        });

        this._timer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, REFRESH_SECONDS, () => {
            this._refresh();
            return GLib.SOURCE_CONTINUE;
        });
        this._refresh();
    }

    destroy() {
        GLib.source_remove(this._timer);
        if (this._ticker) GLib.source_remove(this._ticker);
        this._api?.abort();
        super.destroy();
    }

    async _request(method, path, form) {
        // Read until there is one, so filling in the config needs no restart.
        this._config ??= readConfig();
        this._api ??= this._config && new Api(this._config);
        if (this._api) {
            try {
                const result = await this._api.call(method, path, form);
                // A reply for another list (sent before the popup opened or a tab switch) only brings the
                // pin and counts, so the list on screen isn't wiped.
                const keepRows = result.list !== this._mode && this._state?.list === this._mode;
                this._state = keepRows ? {...this._state, pinned: result.pinned, counts: result.counts, focus: result.focus} : result;
                this._skew = result.now - Date.now(); // the server's clock, which every device's timer counts to
                this._lastOk = clock(new Date());
                this._status = `Updated ${this._lastOk}`;
            } catch (e) {
                // The last list stays up, marked stale.
                this._status = `Offline (${e.message})${this._lastOk ? ` · last updated ${this._lastOk}` : ''}`;
            }
        }
        this._render();
    }

    // Only the list on screen comes back, and none while the popup is closed.
    _refresh() {
        return this._request('GET', `/api/tray?list=${this.menu.isOpen ? this._mode : 'none'}`);
    }

    _post(path, form) {
        return this._request('POST', `${path}${path.includes('?') ? '&' : '?'}list=${this._mode}`, form);
    }

    _open(path) {
        this.menu.close();
        openPage(this._config.url + path);
    }

    // The pinned task's timer: ms left (negative once it's up), or null without one.
    _timeLeft() {
        const p = this._state?.pinned;
        if (p?.timerEndsAt) return p.timerEndsAt - (Date.now() + (this._skew ?? 0));
        return p?.timerRemaining ?? null;
    }

    // The top bar: the current task, "◎" in a focus session, and its timer's time left.
    _updateLabel() {
        const pinned = this._state?.pinned, focus = this._state?.focus;
        let text = null;
        if (focus?.done) text = '◎ Done: what next?';
        else if (pinned) {
            const left = this._timeLeft();
            text = (focus ? '◎ ' : '') + cut(pinned.title, TOP_BAR_CHARS) + (left == null ? '' : left <= 0 ? " · time's up" : ` · ${countdown(left)}`);
        }
        this._pinLabel.visible = !!text;
        if (text) this._pinLabel.text = text;
        if (this._countdownLabel) this._countdownLabel.text = this._countdownText();
    }

    _countdownText() {
        const left = this._timeLeft();
        return left == null ? '' : left <= 0 ? "time's up" : countdown(left);
    }

    // Ticks once a second, only while a timer runs.
    _syncTicker() {
        const running = !!this._state?.pinned?.timerEndsAt;
        if (running && !this._ticker) {
            this._ticker = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 1, () => {
                this._updateLabel();
                return GLib.SOURCE_CONTINUE;
            });
        } else if (!running && this._ticker) {
            GLib.source_remove(this._ticker);
            this._ticker = null;
        }
    }

    _render() {
        const pinned = this._state?.pinned;
        this._updateLabel();
        this._syncTicker();
        if (!this.menu.isOpen) return;

        this._countdownLabel = null; // it goes with the popup's old contents
        this._popup.destroy_all_children();
        if (!this._api) {
            this._popup.add_child(new St.Label({style_class: 'rb-empty', text: `Put {"url": …, "token": …} in\n${CONFIG}`}));
            return;
        }
        // A focus session, on every device: the popup is just it.
        if (this._state?.focus) {
            this._popup.add_child(this._focusView(this._state.focus, pinned));
            this._popup.add_child(new St.Label({style_class: 'rb-status', text: this._status}));
            return;
        }
        this._popup.add_child(this._header());
        if (this._adding) this._popup.add_child(this._quickAdd());
        if (pinned) this._popup.add_child(this._pinnedRow(pinned));

        // Until this tab's list has arrived, the last one isn't shown under it.
        const loaded = this._state?.list === this._mode;
        const rows = loaded ? this._state.rows : [];
        const total = this._state?.counts[this._mode] ?? 0;
        const list = new St.BoxLayout({vertical: true, x_expand: true});
        if (!loaded) list.add_child(new St.Label({style_class: 'rb-empty', text: 'Loading…'}));
        else if (rows.length === 0) list.add_child(new St.Label({style_class: 'rb-empty', text: 'Nothing here'}));
        for (const row of rows) {
            list.add_child(this._row(row));
            if (this._expanded.has(row.id)) for (const child of row.children) list.add_child(this._childRow(row, child));
        }
        // The server sends at most 100 rows; the rest are a click away in the full list.
        if (loaded && total > rows.length) {
            list.add_child(this._button({label: `${total - rows.length} more in the full list…`, style_class: 'rb-more'}, () => this._open(`/${this._mode}`)));
        }
        const scroll = new St.ScrollView({style_class: 'rb-scroll', hscrollbar_policy: St.PolicyType.NEVER, vscrollbar_policy: St.PolicyType.AUTOMATIC});
        scroll.set_child(list);
        this._popup.add_child(scroll);
        this._popup.add_child(new St.Label({style_class: 'rb-status', text: this._status}));
    }

    // ☰ opens the full list; the tabs switch Doing/Active/All; then refresh and quick add, like the widget.
    _header() {
        const header = new St.BoxLayout({style_class: 'rb-header'});
        header.add_child(this._button({label: '☰', style_class: 'rb-menu'}, () => this._open(`/${this._mode}`)));
        for (const mode of ['doing', 'active', 'all']) {
            const count = this._state ? ` ${this._state.counts[mode]}` : '';
            header.add_child(this._button(
                {label: `${mode.toUpperCase()}${count}`, style_class: mode === this._mode ? 'rb-tab rb-tab-on' : 'rb-tab'},
                () => {
                    this._mode = mode;
                    this._render();
                    this._refresh();
                }
            ));
        }
        header.add_child(new St.Widget({x_expand: true}));
        header.add_child(this._iconButton('refresh-symbolic', 'rb-tool', () => this._refresh()));
        header.add_child(this._iconButton('add-symbolic', 'rb-add', () => {
            this._adding = !this._adding;
            this._render();
        }));
        return header;
    }

    // The phone's quick add: the text (the same syntax everywhere), then its chips: star, start, due,
    // today only and folder (or checklist, whose items the text then is). Stays open after each add,
    // for adding several; the folder stays too. What's typed survives re-renders (a refresh rebuilds
    // the popup).
    _quickAdd() {
        const box = new St.BoxLayout({vertical: true, style_class: 'rb-quickadd'});
        const chips = this._chips;
        const folders = this._state?.folders ?? [];
        const folder = folders.find((f) => f.id === (chips.folder ?? this._state?.defaultFolder));
        const form = () => ({
            text: this._draft.trim(), mode: this._mode, star: chips.star ? '1' : '', start: chips.start ?? '', due: chips.due ?? '',
            today: chips.today ? '1' : '', folder: chips.folder ?? '',
        });
        const add = () => {
            if (!this._draft.trim()) return;
            const sent = form();
            this._draft = '';
            this._chips = {folder: chips.folder};
            this._picking = null;
            this._post('/api/quickadd', sent).then(() => this._focusQuickAdd());
        };

        const entry = new St.Entry({style_class: 'rb-entry', hint_text: 'Add a task…', can_focus: true, x_expand: true, text: this._draft});
        entry.clutter_text.connect('text-changed', () => {
            this._draft = entry.get_text();
        });
        entry.clutter_text.connect('activate', add);
        entry.clutter_text.connect('key-press-event', (_actor, event) => {
            if (event.get_key_symbol() !== Clutter.KEY_Escape) return Clutter.EVENT_PROPAGATE;
            this._adding = false;
            this._render();
            return Clutter.EVENT_STOP;
        });
        const top = new St.BoxLayout();
        top.add_child(entry);
        top.add_child(this._chip(chips.star ? 'star-on' : 'star-off', null, chips.star, () => {
            chips.star = !chips.star;
            this._render();
        }));
        box.add_child(top);

        const pick = (what) => () => {
            this._picking = this._picking === what ? null : what;
            this._render();
        };
        const row = new St.BoxLayout({style_class: 'rb-chips'});
        row.add_child(this._chip('calendar-symbolic', chips.start ? DAY_LABELS[chips.start] : 'Start', !!chips.start, pick('start')));
        row.add_child(this._chip('flag-symbolic', chips.due ? DAY_LABELS[chips.due] : 'Due', !!chips.due, pick('due')));
        row.add_child(this._chip('snowflake-symbolic', chips.today ? 'Today only' : null, chips.today, () => {
            chips.today = !chips.today;
            this._render();
        }));
        row.add_child(this._chip(folder?.checklist ? 'checklist-symbolic' : 'folder-symbolic', folder?.title ?? 'Folder', false, pick('folder')));
        box.add_child(row);

        // The chip being set: a few days (St has no date picker), or the folders and checklists.
        if (this._picking) {
            const choices = new St.Widget({style_class: 'rb-choices', layout_manager: new Clutter.FlowLayout({column_spacing: 4, row_spacing: 4})});
            if (this._picking === 'folder') {
                for (const f of folders) {
                    choices.add_child(this._chip(f.checklist ? 'checklist-symbolic' : 'folder-symbolic', f.title, f.id === folder?.id, () => {
                        chips.folder = f.id;
                        this._picking = null;
                        this._render();
                    }));
                }
            } else {
                const kind = this._picking;
                for (const [word, label] of Object.entries(DAY_LABELS)) {
                    choices.add_child(this._chip(null, label, chips[kind] === word, () => {
                        chips[kind] = chips[kind] === word ? null : word;
                        this._picking = null;
                        this._render();
                    }));
                }
            }
            box.add_child(choices);
        }

        const actions = new St.BoxLayout({style_class: 'rb-quickadd-actions'});
        // The full editor on a draft of what's typed, like the phone's "Edit all details".
        actions.add_child(this._button({label: 'Edit all details', style_class: 'rb-link'}, () => {
            const query = Object.entries({...form(), mode: this._mode.toUpperCase()}).filter(([, v]) => v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
            this._draft = '';
            this._chips = {folder: chips.folder};
            this._open(`/tasks/new?${query}`);
        }));
        actions.add_child(new St.Widget({x_expand: true}));
        actions.add_child(this._button({label: 'Add', style_class: 'rb-btn rb-btn-primary'}, add));
        box.add_child(actions);

        GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
            if (entry.get_stage() && !this._picking) entry.grab_key_focus();
            return GLib.SOURCE_REMOVE;
        });
        this._entry = entry;
        return box;
    }

    // One of quick add's chips: an icon, and its value (or name) once there's something to say.
    _chip(icon, label, on, onClick) {
        const box = new St.BoxLayout({style_class: 'rb-chip-box'});
        if (icon) box.add_child(new St.Icon({gicon: appIcon(icon), style_class: 'rb-chip-icon'}));
        if (label) box.add_child(new St.Label({text: label, y_align: Clutter.ActorAlign.CENTER}));
        return this._button({style_class: on ? 'rb-chip rb-chip-on' : 'rb-chip', child: box}, onClick);
    }

    _focusQuickAdd() {
        if (this._adding && this._entry?.get_stage()) this._entry.grab_key_focus();
    }

    // Checkbox (completes it; in front, as every checkbox is), title (opens it), its timer, Focus, then
    // the pin, which unpins it.
    _pinnedRow(pinned) {
        const row = new St.BoxLayout({style_class: 'rb-pinned'});
        row.add_child(this._check(null, () => this._post(`/api/tasks/${pinned.id}/complete`)));
        row.add_child(this._titleButton(pinned.title, () => this._open(`/tasks/${pinned.id}?mode=DOING`)));
        row.add_child(this._timerControls(pinned));
        const focus = this._iconButton('center-focus-symbolic', 'rb-tool rb-unpin', () => this._post(`/api/tasks/${pinned.id}/focus`));
        focus.accessible_name = 'Focus';
        row.add_child(focus);
        const unpin = this._iconButton('push-pin-symbolic', 'rb-tool rb-unpin', () => this._post('/api/unpin'));
        unpin.accessible_name = 'Unpin';
        row.add_child(unpin);
        return row;
    }

    // The shared timer (on the task, like every device's): time left and Pause / Resume, "+10m" once
    // it's up, or ▶ to start one on a timed task.
    _timerControls(pinned) {
        const box = new St.BoxLayout({style_class: 'rb-timer', y_align: Clutter.ActorAlign.CENTER});
        const left = this._timeLeft();
        if (left == null) {
            if (pinned.durationMinutes) box.add_child(this._button({label: `▶ ${duration(pinned.durationMinutes)}`, style_class: 'rb-dur'}, () => this._post(`/api/tasks/${pinned.id}/timer`)));
            return box;
        }
        this._countdownLabel = new St.Label({style_class: 'rb-countdown', text: this._countdownText(), y_align: Clutter.ActorAlign.CENTER});
        box.add_child(this._countdownLabel);
        if (left <= 0) box.add_child(this._button({label: '+10m', style_class: 'rb-dur'}, () => this._post('/api/timer/add?minutes=10')));
        else if (pinned.timerEndsAt) box.add_child(this._iconButton('pause-symbolic', 'rb-tool', () => this._post('/api/timer/pause')));
        else box.add_child(this._iconButton('play-symbolic', 'rb-tool', () => this._post('/api/timer/resume')));
        return box;
    }

    // The popup during a focus session: just the task, its timer, Done and Leave. Once it's done, the
    // same question every device asks: the next task (picked in the desktop app), or finish.
    _focusView(focus, pinned) {
        const box = new St.BoxLayout({vertical: true, style_class: 'rb-focus'});
        box.add_child(new St.Label({style_class: 'rb-focus-label', text: 'FOCUS', x_align: Clutter.ActorAlign.CENTER}));
        const title = new St.Label({style_class: 'rb-focus-title', text: focus.done ? 'Done!' : focus.title, x_align: Clutter.ActorAlign.CENTER});
        title.clutter_text.line_wrap = true;
        box.add_child(title);
        // The start of its note: what to ask, the number to call.
        if (focus.notes && !focus.done) {
            const notes = new St.Label({style_class: 'rb-focus-notes', text: focus.notes});
            notes.clutter_text.line_wrap = true;
            box.add_child(notes);
        }
        const actions = new St.BoxLayout({style_class: 'rb-focus-actions', x_align: Clutter.ActorAlign.CENTER});
        if (focus.done) {
            box.add_child(new St.Label({style_class: 'rb-focus-sub', text: 'Focus on the next task, or finish?', x_align: Clutter.ActorAlign.CENTER}));
            actions.add_child(this._button({label: 'Next task', style_class: 'rb-btn rb-btn-primary'}, () => this._open('/focus')));
            actions.add_child(this._button({label: "I'm done", style_class: 'rb-btn'}, () => this._post('/api/unpin')));
        } else {
            if (pinned) {
                const timer = this._timerControls(pinned);
                timer.x_align = Clutter.ActorAlign.CENTER;
                box.add_child(timer);
            }
            actions.add_child(this._check(null, () => this._post('/api/focus/done')));
            actions.add_child(new St.Label({text: 'Done', style_class: 'rb-focus-sub', y_align: Clutter.ActorAlign.CENTER}));
            actions.add_child(new St.Widget({width: 18}));
            actions.add_child(this._button({label: 'Open', style_class: 'rb-btn'}, () => this._open('/focus')));
            // Leaving ends it on every device, and unpins.
            actions.add_child(this._button({label: 'Leave focus', style_class: 'rb-btn'}, () => this._post('/api/unpin')));
        }
        box.add_child(actions);
        return box;
    }

    // One of the widget's rows: folder bar, checkbox (or a checklist's "3/8"), "Parent:", title,
    // subtask count, pin (on hover; the widget has no room for it), star (or "?" for a maybe).
    _row(row) {
        const box = new St.BoxLayout({style_class: 'rb-row', reactive: true, track_hover: true, x_expand: true});
        box.add_child(new St.Widget({style_class: 'rb-bar', style: `background-color: ${hex(row.barColor) ?? '#DED5C1'};`, y_expand: true}));
        const toggle = () => {
            if (!this._expanded.delete(row.id)) this._expanded.add(row.id);
            this._render();
        };
        if (row.isChecklist) {
            const [done, total] = row.subtasks ? [row.subtasks.first, row.subtasks.second] : [0, 0];
            box.add_child(this._button({label: `${done}/${total}`, style_class: 'rb-count'}, toggle));
        } else {
            box.add_child(this._check(row.due, () => this._post(`/api/tasks/${row.id}/complete`)));
        }
        if (row.parentTitle) {
            box.add_child(this._button({label: `${cut(row.parentTitle, PARENT_CHARS)}:`, style_class: 'rb-parent'},
                () => this._open(`/tasks/${row.parentId}?mode=${this._mode.toUpperCase()}`)));
        }
        const title = this._titleButton(row.title, () => this._open(`/tasks/${row.id}?mode=${this._mode.toUpperCase()}`));
        if (row.isBackburner) title.add_style_class_name('rb-dim');
        box.add_child(title);
        // It has a note (opening the task shows it).
        if (row.hasNotes) box.add_child(new St.Icon({gicon: appIcon('notes-symbolic'), style_class: 'rb-notes-icon', y_align: Clutter.ActorAlign.CENTER}));
        if (row.durationMinutes) box.add_child(this._button({label: `▶ ${duration(row.durationMinutes)}`, style_class: 'rb-dur'}, () => this._post(`/api/tasks/${row.id}/timer`)));
        if (!row.isChecklist && row.subtasks) {
            const arrow = this._expanded.has(row.id) ? '▴' : '▾';
            box.add_child(this._button({label: `${row.subtasks.first}/${row.subtasks.second} ${arrow}`, style_class: 'rb-subtasks'}, toggle));
        }
        const pin = this._iconButton('push-pin-symbolic', 'rb-tool rb-row-pin', () => this._post(`/api/tasks/${row.id}/pin`));
        pin.opacity = 0;
        box.connect('notify::hover', () => {
            pin.opacity = box.hover ? 255 : 0;
        });
        box.add_child(pin);
        if (row.isMaybe) {
            box.add_child(new St.Label({style_class: 'rb-maybe', text: '?', y_align: Clutter.ActorAlign.CENTER}));
        } else {
            box.add_child(this._iconButton(row.isStarred ? 'star-on' : 'star-off',
                row.isStarred ? 'rb-star rb-star-on' : 'rb-star', () => this._post(`/api/tasks/${row.id}/star`)));
        }
        return box;
    }

    // An expanded row's subtask or item: indented, ticked in place (just this one), struck when done.
    _childRow(parent, child) {
        const box = new St.BoxLayout({style_class: 'rb-row rb-child', x_expand: true});
        box.add_child(new St.Widget({style_class: 'rb-bar', style: `background-color: ${hex(parent.barColor) ?? '#DED5C1'};`, y_expand: true}));
        const check = this._check(null, () => this._post(`/api/tasks/${child.id}/toggle-item`));
        check.add_style_class_name('rb-check-small');
        if (child.isComplete) {
            check.add_style_class_name('rb-check-done');
            check.set_child(new St.Icon({gicon: appIcon('check-symbolic'), style_class: 'rb-tick'}));
        }
        box.add_child(check);
        const label = new St.Label({style_class: child.isComplete ? 'rb-child-title rb-done' : 'rb-child-title', x_expand: true, y_align: Clutter.ActorAlign.CENTER});
        const text = GLib.markup_escape_text(child.title, -1);
        label.clutter_text.set_markup(child.isComplete ? `<s>${text}</s>` : text);
        label.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        box.add_child(label);
        return box;
    }

    // The checkbox: a ring, orange with a pale fill when due today, rust when overdue.
    _check(due, onClick) {
        const style = due === 'TODAY' ? 'rb-check rb-today' : due === 'OVERDUE' ? 'rb-check rb-overdue' : 'rb-check';
        return this._button({style_class: style, accessible_name: 'Complete'}, onClick);
    }

    _titleButton(text, onClick) {
        const label = new St.Label({text, x_expand: true, x_align: Clutter.ActorAlign.START});
        label.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        return this._button({child: label, style_class: 'rb-title', x_expand: true, x_align: Clutter.ActorAlign.FILL}, onClick);
    }

    _button(props, onClick) {
        const button = new St.Button({y_align: Clutter.ActorAlign.CENTER, ...props});
        // After the click finishes: the handler usually re-renders, destroying this very button.
        button.connect('clicked', () => GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
            onClick();
            return GLib.SOURCE_REMOVE;
        }));
        return button;
    }

    _iconButton(icon, style, onClick) {
        return this._button({style_class: style, child: new St.Icon({gicon: appIcon(icon), style_class: 'rb-icon'})}, onClick);
    }
});

export default class RaspberryExtension extends Extension {
    enable() {
        this._indicator = new Indicator(this);
        Main.panel.addToStatusArea(this.uuid, this._indicator);
    }

    disable() {
        this._indicator.destroy();
        this._indicator = null;
    }
}
