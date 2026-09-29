#!/usr/bin/env python3
"""Writes the top bar's icons (desktop/raspberry@kzhovn/icons) from the web's Material icon paths
(the Icon enum in WebViews.kt), so the top bar, web and phone share one look. Run after changing either."""
import pathlib, re

root = pathlib.Path(__file__).resolve().parent.parent
paths = dict(re.findall(r'^\s+([A-Z_]+)\("([^"]+)"\)', (root / "server/src/main/kotlin/com/kzhovn/todoapp/server/web/WebViews.kt").read_text(), re.M))
out = root / "desktop/raspberry@kzhovn/icons"
out.mkdir(exist_ok=True)
svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24"><path {} d="{}"/></svg>\n'

# "-symbolic": GNOME recolours these to the CSS colour; the muted fill is what shows otherwise.
for name in ["ADD", "REFRESH", "CALENDAR", "FLAG", "SNOWFLAKE", "FOLDER", "CHECKLIST", "CENTER_FOCUS", "PUSH_PIN", "PLAY", "PAUSE", "CHECK", "NOTES"]:
    (out / f"{name.lower().replace('_', '-')}-symbolic.svg").write_text(svg.format('fill="#8A7A5C"', paths[name]))
# The star keeps the web's own look: an outline, filled pale gold when starred.
(out / "star-off.svg").write_text(svg.format('fill="none" stroke="#B5A98C" stroke-width="1.7" stroke-linejoin="round"', paths["STAR"]))
(out / "star-on.svg").write_text(svg.format('fill="#C9A01E" fill-opacity="0.28" stroke="#C9A01E" stroke-width="1.7" stroke-linejoin="round"', paths["STAR"]))
