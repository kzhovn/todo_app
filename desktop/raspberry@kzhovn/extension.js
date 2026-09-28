// The Raspberry tray: the phone widget's Doing/Active/All lists in a top-bar popup, with the pinned task's
// title in the top bar. Rows come ready-made from the server's /api/tray (the widget's own rows), so
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
        this._state = null;
        this._mode = 'doing';
        this._expanded = new Set(); // rows whose subtasks/items are shown in place, like the widget's
        this._status = '';

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
        this.menu.connect('open-state-changed', (_menu, open) => {
            if (open) this._refresh();
        });

        this._timer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, REFRESH_SECONDS, () => {
            this._refresh();
            return GLib.SOURCE_CONTINUE;
        });
        this._render();
        this._refresh();
    }

    destroy() {
        GLib.source_remove(this._timer);
        this._api?.abort();
        super.destroy();
    }

    async _request(method, path, form) {
        // Read until there is one, so filling in the config needs no restart.
        this._config ??= readConfig();
        this._api ??= this._config && new Api(this._config);
        if (!this._api) return this._render();
        try {
            this._state = await this._api.call(method, path, form);
            this._lastOk = clock(new Date());
            this._status = `Updated ${this._lastOk}`;
        } catch (e) {
            // The last list stays up, marked stale.
            this._status = `Offline (${e.message})${this._lastOk ? ` · last updated ${this._lastOk}` : ''}`;
        }
        this._render();
    }

    _refresh() {
        return this._request('GET', '/api/tray');
    }

    _post(path, form) {
        return this._request('POST', path, form);
    }

    _open(path) {
        this.menu.close();
        openPage(this._config.url + path);
    }

    _render() {
        const pinned = this._state?.pinned;
        this._pinLabel.visible = !!pinned;
        if (pinned) this._pinLabel.text = cut(pinned.title, TOP_BAR_CHARS);

        this._popup.destroy_all_children();
        if (!this._api) {
            this._popup.add_child(new St.Label({style_class: 'rb-empty', text: `Put {"url": …, "token": …} in\n${CONFIG}`}));
            return;
        }
        this._popup.add_child(this._header());
        if (this._adding) this._popup.add_child(this._quickAdd());
        if (pinned) this._popup.add_child(this._pinnedRow(pinned));

        const rows = this._state?.[this._mode] ?? [];
        const list = new St.BoxLayout({vertical: true, x_expand: true});
        if (this._state && rows.length === 0) list.add_child(new St.Label({style_class: 'rb-empty', text: 'Nothing here'}));
        for (const row of rows) {
            list.add_child(this._row(row));
            if (this._expanded.has(row.id)) for (const child of row.children) list.add_child(this._childRow(row, child));
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
            const count = this._state ? ` ${this._state[mode].length}` : '';
            header.add_child(this._button(
                {label: `${mode.toUpperCase()}${count}`, style_class: mode === this._mode ? 'rb-tab rb-tab-on' : 'rb-tab'},
                () => {
                    this._mode = mode;
                    this._render();
                }
            ));
        }
        header.add_child(new St.Widget({x_expand: true}));
        header.add_child(this._iconButton('view-refresh-symbolic', 'rb-tool', () => this._refresh()));
        header.add_child(this._iconButton('list-add-symbolic', 'rb-add', () => {
            this._adding = !this._adding;
            this._render();
        }));
        return header;
    }

    // Stays open after each add, for adding several; the same parser as quick add everywhere. What's
    // typed survives re-renders (a refresh rebuilds the popup).
    _quickAdd() {
        const entry = new St.Entry({style_class: 'rb-entry', hint_text: 'Add a task…', can_focus: true, x_expand: true, text: this._draft ?? ''});
        entry.clutter_text.connect('text-changed', () => {
            this._draft = entry.get_text();
        });
        entry.clutter_text.connect('activate', () => {
            const text = entry.get_text().trim();
            if (!text) return;
            this._draft = '';
            this._post('/api/quickadd', {text, mode: this._mode}).then(() => this._focusQuickAdd());
        });
        entry.clutter_text.connect('key-press-event', (_actor, event) => {
            if (event.get_key_symbol() !== Clutter.KEY_Escape) return Clutter.EVENT_PROPAGATE;
            this._adding = false;
            this._render();
            return Clutter.EVENT_STOP;
        });
        GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
            if (entry.get_stage()) entry.grab_key_focus();
            return GLib.SOURCE_REMOVE;
        });
        this._entry = entry;
        return entry;
    }

    _focusQuickAdd() {
        if (this._adding && this._entry?.get_stage()) this._entry.grab_key_focus();
    }

    _pinnedRow(pinned) {
        const row = new St.BoxLayout({style_class: 'rb-pinned'});
        row.add_child(new St.Icon({icon_name: 'view-pin-symbolic', style_class: 'rb-pin-icon'}));
        row.add_child(this._titleButton(pinned.title, () => this._open(`/tasks/${pinned.id}?mode=DOING`)));
        row.add_child(this._check(null, () => this._post(`/api/tasks/${pinned.id}/complete`)));
        row.add_child(this._iconButton('window-close-symbolic', 'rb-tool', () => this._post('/api/unpin')));
        return row;
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
        if (!row.isChecklist && row.subtasks) {
            const arrow = this._expanded.has(row.id) ? '▴' : '▾';
            box.add_child(this._button({label: `${row.subtasks.first}/${row.subtasks.second} ${arrow}`, style_class: 'rb-subtasks'}, toggle));
        }
        const pin = this._iconButton('view-pin-symbolic', 'rb-tool rb-row-pin', () => this._post(`/api/tasks/${row.id}/pin`));
        pin.opacity = 0;
        box.connect('notify::hover', () => {
            pin.opacity = box.hover ? 255 : 0;
        });
        box.add_child(pin);
        if (row.isMaybe) {
            box.add_child(new St.Label({style_class: 'rb-maybe', text: '?', y_align: Clutter.ActorAlign.CENTER}));
        } else {
            box.add_child(this._iconButton(row.isStarred ? 'starred-symbolic' : 'non-starred-symbolic',
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
            check.set_child(new St.Icon({icon_name: 'object-select-symbolic', style_class: 'rb-tick'}));
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
        return this._button({style_class: style, child: new St.Icon({icon_name: icon, style_class: 'rb-icon'})}, onClick);
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
