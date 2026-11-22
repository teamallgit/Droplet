import json
import os
import re
import xml.etree.ElementTree as ET
from concurrent.futures import ProcessPoolExecutor, as_completed
import numpy as np
from PIL import Image, ImageDraw

import liquid_glass_engine as lge
from build_all_icons import center_and_scale_rgba

REPO_DIR = "/Users/androtech/.gemini/antigravity/scratch/OneLiquid"
SI_DIR = "/Users/androtech/.gemini/antigravity/scratch/ios-liquid-glass-oneui/simple_icons_src"
LAWN_APPFILTER = "/Users/androtech/.gemini/antigravity/scratch/ios-liquid-glass-oneui/lawnicons_src/app/assets/appfilter.xml"
ICONS_DIR = os.path.join(REPO_DIR, "icons")
ASSETS_DIR = os.path.join(REPO_DIR, "assets")
TARGET_EXTRA_ICONS = 906  # 114 core + 906 solid-filled Simple Icons = 1,020 total icons!
OUT_SIZE = 192

# Priority mainstream & niche slugs to rank at the top of previews & selection
PRIORITY_SLUGS = [
    "netflix", "tiktok", "twitch", "pinterest", "paypal", "uber", "ubereats", "doordash",
    "deliveroo", "grubhub", "airbnb", "bookingdotcom", "expedia", "tripadvisor", "waze",
    "lyft", "notion", "obsidian", "vlcmediaplayer", "firefox", "brave", "signal", "bitwarden",
    "plex", "zoom", "duolingo", "strava", "shazam", "threads", "bluesky", "mastodon",
    "facebook", "messenger", "soundcloud", "tidal", "crunchyroll", "canva", "figma",
    "revolut", "wise", "venmo", "cashapp", "robinhood", "coinbase", "binance", "metamask",
    "chase", "americanexpress", "stripe", "klarna", "afterpay", "starbucks", "dominos", "kfc",
    "target", "walmart", "ikea", "etsy", "shopify", "shein", "temu", "aliexpress",
    "claude", "perplexity", "anthropic", "huggingface", "todoist", "ticktick", "trello",
    "asana", "linear", "jira", "clickup", "evernote", "anki", "coursera", "udemy",
    "audible", "goodreads", "pocket", "feedly", "protonvpn", "protonmail", "protondrive",
    "mullvad", "nordvpn", "expressvpn", "tailscale", "wireguard", "adguard", "nextcloud",
    "syncthing", "homeassistant", "philipshue", "smartthings", "garmin", "fitbit",
    "myfitnesspal", "headspace", "chessdotcom", "lichess", "retroarch", "playstation",
    "nintendo", "nintendoswitch", "epicgames", "itchdotio", "curseforge", "modrinth",
    "fdroid", "kodi", "jellyfin", "emby", "foobar2000", "torbrowser", "duckduckgo",
    "vivaldi", "opera", "thunderbird", "1password", "dashlane", "lastpass", "keepassxc",
    "element", "kakaotalk", "line", "wechat", "viber", "patreon", "kickstarter", "medium",
    "substack", "imdb", "letterboxd", "myanimelist", "anilist", "behance", "dribbble",
    "deviantart", "artstation", "unsplash", "flickr", "500px", "vimeo", "dailymotion",
    "rumble", "kick", "bandcamp", "mixcloud", "lastdotfm", "genius", "pocketcasts",
    "overcast", "scribd", "wattpad", "webtoon", "komoot", "alltrails", "flightradar24",
    "adidas", "nike", "zara", "depop", "vinted", "zillow", "indeed", "upwork", "fiverr",
    "photomath", "desmos", "geogebra", "miro", "airtable", "logseq", "joplin",
    "standardnotes", "simplenote", "authy", "cloudflare", "gitlab", "bitbucket", "vercel",
    "docker", "postman", "blender", "unity", "unrealengine", "godotengine", "gogdotcom",
    "ubisoft", "ea", "riotgames", "valorant", "leagueoflegends", "minecraft", "pokemon",
    "nvidia", "razer", "logitech", "obsstudio", "davinciresolve", "audacity", "gimp",
    "inkscape", "krita", "qbittorrent", "transmission", "filezilla", "googletranslate",
    "googlekeep", "googlecalendar", "googlemeet", "googlechat", "googleclassroom",
    "googlefit", "googleearth", "googlenews", "googlepodcasts", "googlepay",
    "googledocs", "googlesheets", "googleslides", "youtubemusic", "youtubestudio",
    "youtubekids", "appletv", "applepodcasts", "androidauto", "accuweather",
]
PRIORITY_RANK = {s: len(PRIORITY_SLUGS) - i for i, s in enumerate(PRIORITY_SLUGS)}


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def sanitize_res(slug):
    s = re.sub(r"[^a-z0-9_]", "_", slug.lower())
    s = re.sub(r"_+", "_", s).strip("_")
    if not s or not s[0].isalpha():
        s = f"ic_{s}"
    return f"si_{s}"


def _render_one(task):
    res_name, svg_path, out_path = task
    try:
        with open(svg_path, "r", encoding="utf-8") as f:
            svg = f.read()
        svg_w = re.sub(r"<path\b", '<path fill="#FFFFFF" fill-opacity="0.98"', svg)
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
    # 1. Remove any old hollow Lawnicons (lg_*.png) and old si_*.png so we rebuild 100% solid-filled cleanly
    removed = 0
    for fn in list(os.listdir(ICONS_DIR)):
        if fn.startswith("lg_") or fn.startswith("si_"):
            os.remove(os.path.join(ICONS_DIR, fn))
            removed += 1
    print(f"Removed {removed} old expansion icons (keeping all 114 core icons untouched)")

    # 2. Load existing core packages/components so we never overwrite bespoke/device-installed icons
    existing_pkgs = set()
    existing_comps = set()
    existing_res = set(f[:-4] for f in os.listdir(ICONS_DIR) if f.endswith(".png"))
    with open(os.path.join(REPO_DIR, "icon_mapping.tsv"), "r", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                existing_pkgs.add(parts[1])
                existing_comps.add(f"ComponentInfo{{{parts[1]}/{parts[2]}}}")
    # Also exclude slugs already covered by our core 114 bespoke/official icons
    already_covered_slugs = {
        "googlechrome", "googleplay", "googledrive", "youtube", "steam", "bose", "ebay",
        "sonos", "mcdonalds", "snapchat", "roblox", "spotify", "appstore", "applemusic",
        "discord", "instagram", "whatsapp", "telegram", "reddit", "x", "github", "openai",
        "google", "googlegemini", "googlemaps", "gmail", "googlephotos", "googlehome",
        "googlewallet", "googleauthenticator", "googleone", "stremio", "surfshark", "termux",
        "ubiquiti", "samsung", "amazon", "aliexpress", "fortnite", "brawlstars",
    }

    # 3. Load Simple Icons catalog (3,472 solid-filled official brand SVGs!)
    si_data = json.load(open(os.path.join(SI_DIR, "data/simple-icons.json"), encoding="utf-8"))
    si_icons_dir = os.path.join(SI_DIR, "icons")
    svg_files = set(f[:-4] for f in os.listdir(si_icons_dir) if f.endswith(".svg"))

    slug_to_title = {}
    lookup_exact = {}
    lookup_loose = {}
    for item in si_data:
        title = item["title"]
        slug = item.get("slug") or norm(title.replace("+", "plus").replace(".", "dot").replace("&", "and"))
        if slug not in svg_files or slug in already_covered_slugs:
            continue
        slug_to_title[slug] = title
        keys = [slug, norm(title)]
        for alias in item.get("aliases", {}).get("aka", []) + item.get("aliases", {}).get("old", []):
            keys.append(norm(alias))
        for k in keys:
            if k and k not in lookup_exact:
                lookup_exact[k] = slug
        for suf in ("dotcom", "dotio", "dotorg", "dotnet", "dotfm", "dotco", "browser", "mediaplayer", "messenger"):
            if slug.endswith(suf) and len(slug) > len(suf) + 2:
                short = slug[:-len(suf)]
                if short not in lookup_exact and short not in lookup_loose:
                    lookup_loose[short] = slug

    # 4. Match Simple Icons against Lawnicons' 24,425 real Android ComponentInfo mappings!
    tree = ET.parse(LAWN_APPFILTER)
    matched = {}  # slug -> set of ComponentInfo
    for elem in tree.getroot():
        if elem.tag != "item":
            continue
        comp = elem.attrib.get("component", "")
        d = elem.attrib.get("drawable", "")
        name = elem.attrib.get("name", "")
        if not comp.startswith("ComponentInfo{"):
            continue
        pkg = comp[len("ComponentInfo{"):].split("/")[0]
        if pkg in existing_pkgs or comp in existing_comps:
            continue

        candidates = [norm(d)]
        for part in name.split("~~"):
            p = norm(part.strip())
            if p:
                candidates.append(p)
                for sfx in ("mobile", "android", "app"):
                    if p.endswith(sfx) and len(p) > len(sfx) + 3:
                        candidates.append(p[:-len(sfx)])

        pkg_parts = [
            norm(x) for x in pkg.split(".")
            if x not in ("com", "org", "net", "io", "app", "android", "mobile", "client",
                         "tv", "co", "uk", "au", "de", "fr", "jp", "cn", "in", "me", "dev", "gov", "edu")
        ]

        hit = None
        for c in candidates:
            if c in lookup_exact:
                hit = lookup_exact[c]
                break
        if not hit:
            for c in candidates:
                if c in lookup_loose:
                    hit = lookup_loose[c]
                    break
        if not hit and len(pkg_parts) == 1 and len(pkg_parts[0]) >= 5 and pkg_parts[0] in lookup_exact:
            hit = lookup_exact[pkg_parts[0]]

        if hit:
            matched.setdefault(hit, set()).add(comp)

    print(f"Matched {len(matched)} solid-filled Simple Icons to {sum(len(v) for v in matched.values())} Android ComponentInfo entries!")

    # 5. Rank: matched priority icons first -> all other matched icons by component count -> top unmatched priority/well-known Simple Icons to reach TARGET_EXTRA_ICONS
    def rank_slug(slug):
        p_score = PRIORITY_RANK.get(slug, 0)
        c_count = len(matched.get(slug, ()))
        has_match = 1 if c_count > 0 else 0
        return (1 if p_score > 0 and has_match else 0, p_score, has_match, c_count, slug)

    all_candidates = list(slug_to_title.keys())
    all_candidates.sort(key=rank_slug, reverse=True)
    selected = all_candidates[:TARGET_EXTRA_ICONS]

    tasks = []
    extra_rows = []
    used_res = set(existing_res)
    for slug in selected:
        res_name = sanitize_res(slug)
        while res_name in used_res:
            res_name += "_2"
        used_res.add(res_name)
        svg_path = os.path.join(si_icons_dir, f"{slug}.svg")
        out_path = os.path.join(ICONS_DIR, f"{res_name}.png")
        tasks.append((res_name, svg_path, out_path))
        title = slug_to_title[slug].replace("\t", " ").replace("\n", " ")
        comps = sorted(matched.get(slug, ()))
        if comps:
            for comp in comps:
                extra_rows.append((res_name, comp, title))
        else:
            # Included in drawable.xml picker even if no appfilter activity matched yet
            extra_rows.append((res_name, "", title))

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
                print(f"Rendered {i}/{len(tasks)} solid-filled icons...")

    rendered_set = set(f[:-4] for f in os.listdir(ICONS_DIR) if f.endswith(".png"))
    with open(os.path.join(REPO_DIR, "extra_mapping.tsv"), "w", encoding="utf-8") as f:
        for res_name, comp, title in extra_rows:
            if res_name in rendered_set:
                f.write(f"{res_name}\t{comp}\t{title}\n")

    print(f"DONE! Rendered {ok_count} solid-filled Simple Icons ({len(errors)} errors). Total icons: {len(rendered_set) - 3}")

    # 6. Regenerate 4 crisp 8x9 preview sheets (288 icons: 114 core + 174 top solid-filled mainstream/niche!)
    core_items = []
    seen = set()
    with open(os.path.join(REPO_DIR, "icon_mapping.tsv"), "r", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4 and parts[0] not in seen:
                seen.add(parts[0])
                core_items.append((parts[0], parts[3]))
    for r, lbl in [("ios_spotify", "Spotify"), ("ios_health", "Health"), ("ios_appstore", "App Store"), ("ic_oneliquid_launcher", "OneLiquid")]:
        if r not in seen and os.path.exists(os.path.join(ICONS_DIR, f"{r}.png")):
            seen.add(r)
            core_items.append((r, lbl))

    extra_preview = []
    for res_name, comp, title in extra_rows:
        if res_name not in seen and res_name in rendered_set:
            seen.add(res_name)
            extra_preview.append((res_name, title))

    all_show = core_items + extra_preview[:174]
    chunks = [all_show[i * 72:(i + 1) * 72] for i in range(4)]
    palettes = [
        ((38, 108, 166), (40, 46, 34)),
        ((46, 94, 158), (35, 36, 42)),
        ((34, 118, 152), (32, 42, 38)),
        ((52, 88, 148), (42, 34, 44)),
    ]

    for idx, (chunk, (base_c, amp_c)) in enumerate(zip(chunks, palettes), start=1):
        cols = 8
        rows_n = (len(chunk) + cols - 1) // cols
        cell_w, cell_h = 116, 128
        W, H = cols * cell_w + 32, rows_n * cell_h + 32
        y, x = np.mgrid[0:H, 0:W].astype(np.float64)
        bg = np.zeros((H, W, 4), dtype=np.float64)
        bg[..., 0] = base_c[0] + amp_c[0] * np.sin(x / 210.0 + idx) + 18 * np.cos(y / 230.0)
        bg[..., 1] = base_c[1] + amp_c[1] * np.cos(x / 240.0) + 24 * np.sin(y / 210.0 + idx)
        bg[..., 2] = base_c[2] + amp_c[2] * np.sin(x / 220.0 + 0.8) + 22 * np.cos(y / 250.0)
        bg[..., 3] = 255
        canvas = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGBA")
        draw = ImageDraw.Draw(canvas)

        for i, (rname, lbl) in enumerate(chunk):
            r, c = divmod(i, cols)
            im = Image.open(os.path.join(ICONS_DIR, f"{rname}.png")).convert("RGBA").resize((92, 92), Image.Resampling.LANCZOS)
            px = 16 + c * cell_w + 12
            py = 16 + r * cell_h + 6
            canvas.alpha_composite(im, (px, py))
            draw.text((px + 2, py + 98), lbl[:13], fill=(255, 255, 255, 240))

        out_p = os.path.join(ASSETS_DIR, f"preview_part{idx}.png")
        canvas.save(out_p, optimize=True)
        print(f"Saved {out_p} ({len(chunk)} icons)")


if __name__ == "__main__":
    main()
