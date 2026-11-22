import os
import re
import xml.etree.ElementTree as ET
from concurrent.futures import ProcessPoolExecutor, as_completed
import numpy as np
from PIL import Image

import liquid_glass_engine as lge
from build_all_icons import center_and_scale_rgba

REPO_DIR = "/Users/androtech/.gemini/antigravity/scratch/OneLiquid"
LAWN_DIR = "/Users/androtech/.gemini/antigravity/scratch/ios-liquid-glass-oneui/lawnicons_src"
ICONS_DIR = os.path.join(REPO_DIR, "icons")
TARGET_EXTRA_ICONS = 906  # 114 existing + 906 = 1,020 total icons!
OUT_SIZE = 192            # Standard One UI Theme Park crisp icon size (192x192)

PRIORITY_KEYWORDS = {
    "netflix", "tiktok", "twitch", "linkedin", "pinterest", "paypal", "uber", "doordash",
    "airbnb", "notion", "obsidian", "vlc", "firefox", "brave", "signal", "bitwarden",
    "plex", "zoom", "slack", "duolingo", "strava", "shazam", "hbo", "max", "threads",
    "disney", "hulu", "prime", "crunchyroll", "soundcloud", "tidal", "deezer", "bandcamp",
    "bluesky", "mastodon", "tumblr", "snap", "wechat", "line", "viber", "skype", "teams",
    "outlook", "onedrive", "office", "word", "excel", "powerpoint", "copilot", "edge",
    "bing", "authenticator", "adobe", "lightroom", "photoshop", "canva", "figma",
    "capcut", "inshot", "snapseed", "vsco", "kinemaster", "davinci", "krita",
    "revolut", "wise", "venmo", "cashapp", "robinhood", "coinbase", "binance", "metamask",
    "trust", "ledger", "chase", "amex", "stripe", "klarna", "afterpay", "commbank",
    "anz", "westpac", "ubereats", "deliveroo", "grubhub", "instacart", "starbucks",
    "dominos", "kfc", "subway", "target", "walmart", "ikea", "shein", "temu", "etsy",
    "shopify", "booking", "expedia", "skyscanner", "tripadvisor", "flightradar", "waze",
    "alltrails", "komoot", "citymapper", "lyft", "grab", "didi", "bolt",
    "claude", "perplexity", "grok", "deepseek", "poe", "character", "midjourney",
    "todoist", "ticktick", "trello", "asana", "linear", "jira", "clickup", "evernote",
    "onenote", "bear", "anki", "quizlet", "khan", "coursera", "udemy", "kindle",
    "audible", "kobo", "libby", "goodreads", "pocket", "instapaper", "feedly",
    "proton", "mullvad", "nordvpn", "expressvpn", "tailscale", "wireguard", "adguard",
    "nextcloud", "syncthing", "home_assistant", "hue", "tuya", "smartthings", "ring",
    "wyze", "eufy", "roborock", "garmin", "fitbit", "whoop", "oura", "myfitnesspal",
    "yazio", "headspace", "calm", "sleep", "flo", "clue",
    "minecraft", "clash", "pubg", "genshin", "honkai", "valorant", "league", "chess",
    "lichess", "pokemongo", "retroarch", "ppsspp", "dolphin", "aethersx2", "citra",
    "drastic", "moonlight", "parsec", "geforce", "xbox", "playstation", "nintendo",
    "epic", "itch", "curseforge", "modrinth", "prism",
    "fdroid", "obtainium", "aurora", "newpipe", "libretube", "revanced", "magisk",
    "lsposed", "tasker", "macrodroid", "kustom", "kwgt", "klwp", "nova", "lawnchair",
    "niagara", "smartlauncher", "solid", "mixplorer", "zarchiver", "sdmaid",
    "fx", "total_commander", "localsend", "kdeconnect", "scrcpy", "adb", "wire",
    "element", "briar", "session", "simplex", "aegis", "ente", "keepass", "1password",
    "dashlane", "lastpass", "tor", "duckduckgo", "vivaldi", "opera", "cromite",
    "mull", "librewolf", "k9", "thunderbird", "fairmail", "tutanota", "fastmail",
    "gram", "nekogram", "ayugram", "tachiyomi", "mihon", "kotatsu", "aniyomi",
    "stremio", "kodi", "jellyfin", "emby", "symfonium", "poweramp", "musicolet",
    "foobar", "aimp", "blackplayer", "retro", "gramophone", "auxio", "innertune",
    "vimusic", "spotube", "seal", "ytdlnis", "metrolist",
}


def sanitize_lawn_res(name):
    s = re.sub(r"[^a-z0-9_]", "_", name.lower())
    s = re.sub(r"_+", "_", s).strip("_")
    if not s or not s[0].isalpha():
        s = f"ic_{s}"
    return f"lg_{s}"


def _render_one(task):
    res_name, svg_path, out_path = task
    try:
        with open(svg_path, "r", encoding="utf-8") as f:
            svg = f.read()
        # Convert monochrome black fills/strokes to pure luminous white
        svg_w = re.sub(r"#000(?:000)?\b", "#FFFFFF", svg, flags=re.IGNORECASE)
        svg_w = re.sub(r'"black"', '"#FFFFFF"', svg_w, flags=re.IGNORECASE)
        svg_w = re.sub(r"\bcurrentColor\b", "#FFFFFF", svg_w, flags=re.IGNORECASE)

        raw = lge.render_svg_to_rgba(svg_w, size=384)
        if float(raw[..., 3].max()) < 0.05:
            return None
        glyph = center_and_scale_rgba(raw)
        icon = lge.compose_liquid_glass_icon(glyph, out_size=OUT_SIZE)
        icon.save(out_path, optimize=True)
        return res_name
    except Exception as e:
        return f"ERR:{res_name}:{e}"


def main():
    # Load existing component mappings so we never overwrite our bespoke/device-installed icons
    existing_comps = set()
    existing_res = set(f[:-4] for f in os.listdir(ICONS_DIR) if f.endswith(".png"))
    with open(os.path.join(REPO_DIR, "icon_mapping.tsv"), "r", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                existing_comps.add(f"ComponentInfo{{{parts[1]}/{parts[2]}}}")

    # Resize existing core icons to uniform 192x192 so all 1,020 icons match cleanly & APK stays ~25MB
    for fname in os.listdir(ICONS_DIR):
        if not fname.endswith(".png"):
            continue
        p = os.path.join(ICONS_DIR, fname)
        im = Image.open(p)
        if im.size != (OUT_SIZE, OUT_SIZE):
            im.resize((OUT_SIZE, OUT_SIZE), Image.Resampling.LANCZOS).save(p, optimize=True)

    # Parse Lawnicons appfilter.xml
    tree = ET.parse(os.path.join(LAWN_DIR, "app/assets/appfilter.xml"))
    root = tree.getroot()
    svg_dir = os.path.join(LAWN_DIR, "svgs")
    svg_files = set(f[:-4] for f in os.listdir(svg_dir) if f.endswith(".svg"))

    drawable_to_comps = {}
    drawable_labels = {}
    for elem in root:
        if elem.tag != "item":
            continue
        comp = elem.attrib.get("component", "")
        d = elem.attrib.get("drawable", "")
        name = elem.attrib.get("name", d)
        if not comp.startswith("ComponentInfo{") or d not in svg_files:
            continue
        if comp in existing_comps:
            continue
        # Skip if this package is already themed by a bespoke/device icon
        pkg = comp[len("ComponentInfo{"):].split("/")[0]
        if any(ec.startswith(f"ComponentInfo{{{pkg}/") for ec in existing_comps):
            continue
        drawable_to_comps.setdefault(d, []).append(comp)
        if d not in drawable_labels:
            drawable_labels[d] = name

    # Score each drawable: priority mainstream/niche keyword boost + ComponentInfo popularity
    def score_drawable(d):
        comps = drawable_to_comps[d]
        dl = d.lower()
        kw_hit = 1000 if any(kw in dl for kw in PRIORITY_KEYWORDS) else 0
        # Slight penalty for overly region-specific numeric prefixes unless keyword hit
        return (kw_hit + len(comps) * 10, dl)

    ranked = sorted(drawable_to_comps.keys(), key=score_drawable, reverse=True)
    selected = ranked[:TARGET_EXTRA_ICONS]
    print(f"Selected {len(selected)} extra Lawnicons drawables (total ~{len(existing_res) - 3 + len(selected)} icons)")

    tasks = []
    extra_rows = []
    used_res = set(existing_res)
    for d in selected:
        res_name = sanitize_lawn_res(d)
        while res_name in used_res:
            res_name += "_2"
        used_res.add(res_name)
        svg_path = os.path.join(svg_dir, f"{d}.svg")
        out_path = os.path.join(ICONS_DIR, f"{res_name}.png")
        tasks.append((res_name, svg_path, out_path))
        lbl = drawable_labels.get(d, d).replace("\t", " ").replace("\n", " ")
        for comp in drawable_to_comps[d]:
            extra_rows.append((res_name, comp, lbl))

    ok_count = 0
    errors = []
    with ProcessPoolExecutor(max_workers=8) as ex:
        futs = [ex.submit(_render_one, t) for t in tasks]
        for i, fut in enumerate(as_completed(futs), start=1):
            res = fut.result()
            if res is None:
                continue
            if res.startswith("ERR:"):
                errors.append(res)
            else:
                ok_count += 1
            if i % 150 == 0:
                print(f"Rendered {i}/{len(tasks)}...")

    # Save extra_mapping.tsv
    rendered_set = set(f[:-4] for f in os.listdir(ICONS_DIR) if f.endswith(".png"))
    with open(os.path.join(REPO_DIR, "extra_mapping.tsv"), "w", encoding="utf-8") as f:
        for res_name, comp, lbl in extra_rows:
            if res_name in rendered_set:
                f.write(f"{res_name}\t{comp}\t{lbl}\n")

    print(f"DONE! Rendered {ok_count} new icons ({len(errors)} errors). Total icons in {ICONS_DIR}: {len(rendered_set) - 3}")
    if errors:
        print("Sample errors:", errors[:5])


if __name__ == "__main__":
    main()
