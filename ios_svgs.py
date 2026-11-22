import math
import os
import re

OFFICIAL_SVG_DIR = "./official_svgs"


def _svg_wrap(body, viewbox="0 0 512 512"):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{viewbox}" width="512" height="512">{body}</svg>'


def _official_simple_icon(name):
    """Load an exact official Simple Icons SVG and fill it pure white for our optical normalizer."""
    p = os.path.join(OFFICIAL_SVG_DIR, f"{name}.svg")
    with open(p, "r", encoding="utf-8") as f:
        svg = f.read()
    # Ensure pure white fill on all paths
    svg = re.sub(r"<path\b", '<path fill="#FFFFFF" fill-opacity="0.98"', svg)
    return svg


# ---------------------------------------------------------------------------
# Official Brand Vector Icons (loaded directly from Simple Icons SVGs &
# optically scaled via build_all_icons.center_and_scale_rgba!)
# ---------------------------------------------------------------------------
OFFICIAL_BRAND_SVGS = {
    "com.android.chrome": ("ios_chrome", "googlechrome", 320),
    "com.android.vending": ("ios_playstore", "googleplay", 292),
    "com.google.android.apps.docs": ("ios_drive", "googledrive", 304),
    "com.google.android.youtube": ("ios_youtube", "youtube", 316),
    "com.valvesoftware.android.steam.community": ("ios_steam", "steam", 322),
    "com.bose.monet": ("ios_bose", "bose", 364),
    "com.ebay.mobile": ("ios_ebay", "ebay", 356),
    "com.sonos.acr2": ("ios_sonos", "sonos", 362),
    "com.mcdonalds.au.gma": ("ios_mcdonalds", "mcdonalds", 296),
    "com.snapchat.android": ("ios_snapchat", "snapchat", 308),
    "com.roblox.client": ("ios_roblox", "roblox", 274),
}

OFFICIAL_BONUS_SVGS = {
    "ios_spotify": ("spotify", 322),
    "ios_appstore": ("appstore", 304),
}


# ---------------------------------------------------------------------------
# Apple iOS 26 Clear Stock System Icons (calibrated 1:1 to WWDC25 2x reference
# and strictly aligned to unified optical keylines: ~322px circle / ~304px box)
# ---------------------------------------------------------------------------
def svg_facetime():
    return _svg_wrap("""
    <rect x="98" y="168" width="214" height="176" rx="38" fill="#FFFFFF" fill-opacity="0.98"/>
    <path d="M327 227 L396 177 C404 171 414 177 414 187 L414 325 C414 335 404 341 396 335 L327 285 Z"
          fill="#FFFFFF" fill-opacity="0.88"/>
    """)


def svg_photos():
    # Exact Apple iOS 26 Clear Photos: 8 overlapping luminous frosted petals (~326px diameter)
    opacities = [0.95, 0.72, 0.86, 0.62, 0.56, 0.74, 0.68, 0.88]
    petals = []
    for i, op in enumerate(opacities):
        angle = i * 45
        petals.append(
            f'<rect x="217" y="93" width="78" height="151" rx="39" fill="#FFFFFF" fill-opacity="{op:.2f}" '
            f'transform="rotate({angle} 256 256)"/>'
        )
    return _svg_wrap("\n".join(petals))


def svg_calendar():
    return _svg_wrap("""
    <text x="256" y="160" text-anchor="middle"
          font-family="SF Pro Display, -apple-system, Helvetica Neue, Arial, sans-serif"
          font-size="78" font-weight="500" fill="#FFFFFF" fill-opacity="0.88" letter-spacing="1.5">Sun</text>
    <text x="256" y="384" text-anchor="middle"
          font-family="SF Pro Display, -apple-system, Helvetica Neue, Arial, sans-serif"
          font-size="232" font-weight="400" fill="#FFFFFF" fill-opacity="1.0">22</text>
    """)


def svg_camera():
    # Exact Apple iOS 26 Clear Camera (~326px outer diameter)
    return _svg_wrap("""
    <circle cx="256" cy="256" r="154" fill="none" stroke="#FFFFFF" stroke-width="17" stroke-opacity="0.98"/>
    <circle cx="256" cy="256" r="132" fill="#FFFFFF" fill-opacity="0.20" stroke="#FFFFFF" stroke-width="4.5" stroke-opacity="0.65"/>
    <circle cx="256" cy="256" r="102" fill="#FFFFFF" fill-opacity="0.16" stroke="#FFFFFF" stroke-width="3.5" stroke-opacity="0.48"/>
    <circle cx="256" cy="256" r="66" fill="#FFFFFF" fill-opacity="0.10" stroke="#FFFFFF" stroke-width="3" stroke-opacity="0.36"/>
    <path d="M175 196 A106 106 0 0 1 290 152 A84 84 0 0 0 192 218 Z" fill="#FFFFFF" fill-opacity="0.92"/>
    <path d="M346 270 A96 96 0 0 1 252 352 A112 112 0 0 0 362 260 Z" fill="#FFFFFF" fill-opacity="0.86"/>
    <ellipse cx="218" cy="214" rx="26" ry="17" transform="rotate(-38 218 214)" fill="#FFFFFF" fill-opacity="0.94"/>
    <ellipse cx="292" cy="294" rx="17" ry="10" transform="rotate(-38 292 294)" fill="#FFFFFF" fill-opacity="0.68"/>
    """)


def svg_notes():
    dots = []
    for i in range(15):
        x = 48 + i * 29.7
        dots.append(f'<circle cx="{x:.1f}" cy="158" r="4.8" fill="#FFFFFF" fill-opacity="0.55"/>')
    return _svg_wrap(f"""
    <defs>
      <linearGradient id="notesHdr" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#FFFFFF" stop-opacity="0.96"/>
        <stop offset="100%" stop-color="#FFFFFF" stop-opacity="0.72"/>
      </linearGradient>
    </defs>
    <rect x="0" y="0" width="512" height="138" fill="url(#notesHdr)"/>
    {''.join(dots)}
    <rect x="52" y="236" width="408" height="9.5" rx="4.75" fill="#FFFFFF" fill-opacity="0.58"/>
    <rect x="52" y="320" width="408" height="9.5" rx="4.75" fill="#FFFFFF" fill-opacity="0.58"/>
    <rect x="52" y="404" width="408" height="9.5" rx="4.75" fill="#FFFFFF" fill-opacity="0.54"/>
    """)


def svg_findmy():
    # ~324px circular keyline
    return _svg_wrap("""
    <circle cx="256" cy="256" r="162" fill="#FFFFFF" fill-opacity="0.42"/>
    <circle cx="256" cy="256" r="110" fill="#FFFFFF" fill-opacity="0.34"/>
    <path d="M256 256 L180 109 A162 162 0 0 1 332 109 Z" fill="#FFFFFF" fill-opacity="0.46"/>
    <circle cx="256" cy="256" r="42" fill="none" stroke="#FFFFFF" stroke-width="21" stroke-opacity="0.98"/>
    """)


def svg_clock():
    ticks = []
    for i in range(60):
        ang = math.radians(i * 6 - 90)
        is_hour = (i % 5 == 0)
        ca, sa = math.cos(ang), math.sin(ang)
        p = 4.5
        r_scale = (abs(ca) ** p + abs(sa) ** p) ** (-1.0 / p)
        r_outer = 212 * (0.90 + 0.10 * min(r_scale, 1.13))
        r_inner = r_outer - (29 if is_hour else 15)
        x1, y1 = 256 + r_inner * ca, 256 + r_inner * sa
        x2, y2 = 256 + r_outer * ca, 256 + r_outer * sa
        sw = 7.2 if is_hour else 3.8
        op = 0.92 if is_hour else 0.54
        ticks.append(
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="#FFFFFF" stroke-width="{sw}" stroke-linecap="round" stroke-opacity="{op}"/>'
        )
    return _svg_wrap(f"""
    {''.join(ticks)}
    <text x="256" y="144" text-anchor="middle" font-family="SF Pro Display, Helvetica Neue, Arial" font-size="54" font-weight="500" fill="#FFFFFF" fill-opacity="0.96">12</text>
    <text x="388" y="274" text-anchor="middle" font-family="SF Pro Display, Helvetica Neue, Arial" font-size="54" font-weight="500" fill="#FFFFFF" fill-opacity="0.96">3</text>
    <text x="256" y="414" text-anchor="middle" font-family="SF Pro Display, Helvetica Neue, Arial" font-size="54" font-weight="500" fill="#FFFFFF" fill-opacity="0.96">6</text>
    <text x="124" y="274" text-anchor="middle" font-family="SF Pro Display, Helvetica Neue, Arial" font-size="54" font-weight="500" fill="#FFFFFF" fill-opacity="0.96">9</text>
    <line x1="256" y1="256" x2="158" y2="200" stroke="#FFFFFF" stroke-width="13.5" stroke-linecap="round" stroke-opacity="0.98"/>
    <line x1="256" y1="256" x2="374" y2="194" stroke="#FFFFFF" stroke-width="10.5" stroke-linecap="round" stroke-opacity="0.98"/>
    <line x1="256" y1="284" x2="268" y2="142" stroke="#FFFFFF" stroke-width="4.2" stroke-linecap="round" stroke-opacity="0.72"/>
    <circle cx="256" cy="256" r="12.5" fill="#FFFFFF" fill-opacity="0.98"/>
    """)


def svg_maps_layered():
    return _svg_wrap("""
    <defs>
      <mask id="navCut">
        <rect width="512" height="512" fill="white"/>
        <circle cx="212" cy="294" r="122" fill="black"/>
      </mask>
      <mask id="badgeRing">
        <rect width="512" height="512" fill="black"/>
        <circle cx="212" cy="294" r="104" fill="white"/>
        <circle cx="212" cy="294" r="75" fill="black"/>
      </mask>
    </defs>
    <g mask="url(#navCut)">
      <path d="M-20 130 L530 278 L530 338 L-20 190 Z" fill="#FFFFFF" fill-opacity="0.28"/>
      <path d="M98 -20 L214 530 L146 530 L30 -20 Z" fill="#FFFFFF" fill-opacity="0.34"/>
      <path d="M336 -20 L414 -20 L312 530 L242 530 Z" fill="#FFFFFF" fill-opacity="0.42"/>
      <circle cx="430" cy="108" r="82" fill="none" stroke="#FFFFFF" stroke-width="42" stroke-opacity="0.45"/>
      <rect x="20" y="340" width="160" height="160" rx="22" fill="#FFFFFF" fill-opacity="0.22"/>
    </g>
    <circle cx="212" cy="294" r="75" fill="#FFFFFF" fill-opacity="0.16"/>
    <rect width="512" height="512" fill="#FFFFFF" fill-opacity="0.96" mask="url(#badgeRing)"/>
    <path d="M212 230 L258 338 L212 315 L166 338 Z" fill="#FFFFFF" fill-opacity="0.98"/>
    """)


def svg_health():
    return _svg_wrap("""
    <path d="M338 256 C338 256 250 200 250 146 C250 113 275 88 306 88 C323 88 334 98 338 107 C342 98 353 88 370 88 C401 88 426 113 426 146 C426 200 338 256 338 256 Z"
          fill="#FFFFFF" fill-opacity="0.98"/>
    """)


def svg_home():
    return _svg_wrap("""
    <rect x="319" y="128" width="32" height="74" rx="8" fill="#FFFFFF" fill-opacity="0.65"/>
    <path d="M130 242 L256 132 L382 242 L382 358 C382 374 370 386 354 386 L158 386 C142 386 130 374 130 358 Z"
          fill="#FFFFFF" fill-opacity="0.46"/>
    <path d="M166 266 L256 188 L346 266 L346 352 C346 361 339 368 330 368 L182 368 C173 368 166 361 166 352 Z"
          fill="#FFFFFF" fill-opacity="0.56"/>
    <path d="M206 289 L256 247 L306 289 L306 346 C306 351 302 355 297 355 L215 355 C210 355 206 351 206 346 Z"
          fill="#FFFFFF" fill-opacity="0.88"/>
    <path d="M104 246 L244 122 C251 116 261 116 268 122 L408 246 C415 253 416 263 409 270 C403 277 393 277 386 271 L256 156 L126 271 C119 277 109 277 103 270 C96 263 97 253 104 246 Z"
          fill="#FFFFFF" fill-opacity="0.98"/>
    """)


def svg_amazon():
    # Clean stock-style Amazon 'a' + curved smile arrow (~304px balanced)
    return _svg_wrap("""
    <text x="256" y="298" text-anchor="middle"
          font-family="SF Pro Display, Helvetica Neue, Arial, sans-serif"
          font-size="242" font-weight="700" fill="#FFFFFF" fill-opacity="0.98">a</text>
    <path d="M132 332 C192 380 308 382 372 338" fill="none" stroke="#FFFFFF" stroke-width="19" stroke-linecap="round" stroke-opacity="0.95"/>
    <path d="M348 329 L380 335 L368 367" fill="none" stroke="#FFFFFF" stroke-width="17" stroke-linecap="round" stroke-linejoin="round" stroke-opacity="0.95"/>
    """)


def svg_mail():
    # ~312px wide
    return _svg_wrap("""
    <rect x="100" y="156" width="312" height="200" rx="25" fill="#FFFFFF" fill-opacity="0.54"/>
    <path d="M112 348 L225 252 L287 252 L400 348 C392 354 384 356 374 356 L138 356 C128 356 120 354 112 348 Z"
          fill="#FFFFFF" fill-opacity="0.34"/>
    <path d="M118 156 L394 156 C404 156 412 164 412 174 L271 279 C262 287 250 287 241 279 L100 174 C100 164 108 156 118 156 Z"
          fill="#FFFFFF" fill-opacity="0.98"/>
    """)


def svg_files():
    # ~312px wide
    return _svg_wrap("""
    <defs>
      <linearGradient id="folderGrad" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#FFFFFF" stop-opacity="0.90"/>
        <stop offset="55%" stop-color="#FFFFFF" stop-opacity="0.62"/>
        <stop offset="100%" stop-color="#FFFFFF" stop-opacity="0.46"/>
      </linearGradient>
    </defs>
    <path d="M100 166 C100 150 113 138 129 138 L198 138 C210 138 220 145 227 154 L239 170 L383 170 C399 170 412 183 412 199 L412 223 L100 223 Z"
          fill="#FFFFFF" fill-opacity="0.98"/>
    <rect x="100" y="192" width="312" height="182" rx="23" fill="url(#folderGrad)"/>
    <rect x="104" y="192" width="304" height="8.5" rx="4.25" fill="#FFFFFF" fill-opacity="0.94"/>
    """)


def svg_phone():
    # ~292px balanced
    return _svg_wrap("""
    <path d="M178 122 C166 122 153 128 144 137 L130 151 C116 165 114 192 128 226 C146 268 176 306 213 336 C249 365 290 386 327 393 C350 397 370 391 383 378 L395 366 C408 353 408 334 393 322 L349 288 C337 279 321 281 310 291 L294 307 C287 314 277 314 267 309 C244 293 219 268 203 245 C198 235 198 225 205 218 L221 202 C231 191 233 175 224 163 L190 119 C187 115 183 122 178 122 Z"
          fill="#FFFFFF" fill-opacity="0.98" transform="translate(-3, 2)"/>
    """)


def svg_safari():
    # Exact ~326px circular keyline matching Settings, Camera, Steam, Spotify, Chrome!
    ticks = []
    for i in range(48):
        ang = math.radians(i * 7.5)
        is_major = (i % 2 == 0)
        r_out = 156
        r_in = 128 if is_major else 139
        x1, y1 = 256 + r_in * math.cos(ang), 256 + r_in * math.sin(ang)
        x2, y2 = 256 + r_out * math.cos(ang), 256 + r_out * math.sin(ang)
        sw = 5.6 if is_major else 3.4
        ticks.append(
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="#FFFFFF" stroke-width="{sw}" stroke-linecap="round" stroke-opacity="0.90"/>'
        )
    return _svg_wrap(f"""
    <circle cx="256" cy="256" r="163" fill="#FFFFFF" fill-opacity="0.24"/>
    {''.join(ticks)}
    <polygon points="348,164 278,278 234,234" fill="#FFFFFF" fill-opacity="0.98"/>
    <polygon points="164,348 278,278 234,234" fill="#FFFFFF" fill-opacity="0.62"/>
    <circle cx="256" cy="256" r="8.5" fill="#FFFFFF" fill-opacity="0.96"/>
    """)


def svg_messages():
    # ~316px wide
    return _svg_wrap("""
    <path d="M256 134 C168 134 98 188 98 253 C98 288 117 320 150 342 C146 361 135 379 118 390 C150 388 180 378 201 363 C218 369 237 372 256 372 C344 372 414 318 414 253 C414 188 344 134 256 134 Z"
          fill="#FFFFFF" fill-opacity="0.98"/>
    """)


def svg_settings():
    # Exact ~326px circular keyline matching Steam/Chrome/Spotify/Safari/Camera!
    outer_teeth = []
    n_outer = 28
    for i in range(n_outer):
        a0 = math.radians(i * (360.0 / n_outer))
        a1 = math.radians((i + 0.34) * (360.0 / n_outer))
        a2 = math.radians((i + 0.56) * (360.0 / n_outer))
        a3 = math.radians((i + 0.90) * (360.0 / n_outer))
        r_base = 139
        r_tip = 163
        pts = [
            (256 + r_base * math.cos(a0), 256 + r_base * math.sin(a0)),
            (256 + r_tip * math.cos(a1), 256 + r_tip * math.sin(a1)),
            (256 + r_tip * math.cos(a2), 256 + r_tip * math.sin(a2)),
            (256 + r_base * math.cos(a3), 256 + r_base * math.sin(a3)),
        ]
        outer_teeth.append(" ".join(f"{x:.1f},{y:.1f}" for x, y in pts))

    inner_teeth = []
    n_inner = 30
    for i in range(n_inner):
        ang = math.radians(i * (360.0 / n_inner))
        x1, y1 = 256 + 92 * math.cos(ang), 256 + 92 * math.sin(ang)
        x2, y2 = 256 + 109 * math.cos(ang), 256 + 109 * math.sin(ang)
        inner_teeth.append(
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="#FFFFFF" stroke-width="8" stroke-linecap="butt" stroke-opacity="0.62"/>'
        )

    spokes = []
    for deg in (-90, 30, 150):
        ang = math.radians(deg)
        x2, y2 = 256 + 120 * math.cos(ang), 256 + 120 * math.sin(ang)
        spokes.append(
            f'<line x1="256" y1="256" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="#FFFFFF" stroke-width="18" stroke-linecap="round" stroke-opacity="0.98"/>'
        )

    return _svg_wrap(f"""
    <defs>
      <mask id="gearHole">
        <rect width="512" height="512" fill="white"/>
        <circle cx="256" cy="256" r="113" fill="black"/>
      </mask>
    </defs>
    <g mask="url(#gearHole)">
      <polygon points="{' '.join(outer_teeth)}" fill="#FFFFFF" fill-opacity="0.98"/>
      <circle cx="256" cy="256" r="142" fill="#FFFFFF" fill-opacity="0.98"/>
    </g>
    {''.join(inner_teeth)}
    <circle cx="256" cy="256" r="109" fill="none" stroke="#FFFFFF" stroke-width="8" stroke-opacity="0.72"/>
    {''.join(spokes)}
    <circle cx="256" cy="256" r="26" fill="#FFFFFF" fill-opacity="0.98"/>
    <circle cx="256" cy="256" r="11" fill="#FFFFFF" fill-opacity="0.30"/>
    """)


def svg_samsung_store():
    return _svg_wrap("""
    <defs>
      <mask id="bagCut">
        <rect width="512" height="512" fill="white"/>
        <circle cx="200" cy="218" r="12.5" fill="black"/>
        <circle cx="312" cy="218" r="12.5" fill="black"/>
      </mask>
    </defs>
    <rect x="122" y="180" width="268" height="212" rx="38" fill="#FFFFFF" fill-opacity="0.88" mask="url(#bagCut)"/>
    <path d="M200 218 C200 132 312 132 312 218" fill="none" stroke="#FFFFFF" stroke-width="21" stroke-linecap="round" stroke-opacity="0.98"/>
    """)


def svg_calculator():
    # ~292px square keyline
    return _svg_wrap("""
    <rect x="110" y="110" width="132" height="132" rx="30" fill="#FFFFFF" fill-opacity="0.46"/>
    <rect x="167" y="140" width="18" height="72" rx="9" fill="#FFFFFF" fill-opacity="0.98"/>
    <rect x="140" y="167" width="72" height="18" rx="9" fill="#FFFFFF" fill-opacity="0.98"/>
    <rect x="270" y="110" width="132" height="132" rx="30" fill="#FFFFFF" fill-opacity="0.46"/>
    <rect x="300" y="167" width="72" height="18" rx="9" fill="#FFFFFF" fill-opacity="0.98"/>
    <rect x="110" y="270" width="132" height="132" rx="30" fill="#FFFFFF" fill-opacity="0.46"/>
    <g transform="rotate(45 176 336)">
      <rect x="167" y="300" width="18" height="72" rx="9" fill="#FFFFFF" fill-opacity="0.98"/>
      <rect x="140" y="327" width="72" height="18" rx="9" fill="#FFFFFF" fill-opacity="0.98"/>
    </g>
    <defs>
      <mask id="eqCut">
        <rect width="512" height="512" fill="white"/>
        <rect x="300" y="314" width="72" height="16" rx="8" fill="black"/>
        <rect x="300" y="342" width="72" height="16" rx="8" fill="black"/>
      </mask>
    </defs>
    <rect x="270" y="270" width="132" height="132" rx="30" fill="#FFFFFF" fill-opacity="0.98" mask="url(#eqCut)"/>
    """)


def svg_contacts():
    return _svg_wrap("""
    <rect x="398" y="128" width="23" height="55" rx="9" fill="#FFFFFF" fill-opacity="0.46"/>
    <rect x="398" y="194" width="23" height="55" rx="9" fill="#FFFFFF" fill-opacity="0.62"/>
    <rect x="398" y="260" width="23" height="55" rx="9" fill="#FFFFFF" fill-opacity="0.46"/>
    <rect x="398" y="326" width="23" height="55" rx="9" fill="#FFFFFF" fill-opacity="0.36"/>
    <circle cx="242" cy="193" r="59" fill="#FFFFFF" fill-opacity="0.98"/>
    <path d="M130 368 C130 306 178 270 242 270 C306 270 354 306 354 368 C354 379 346 386 335 386 L149 386 C138 386 130 379 130 368 Z"
          fill="#FFFFFF" fill-opacity="0.88"/>
    """)


def svg_voicenote():
    bars = [
        (110, 42, 0.54), (137, 86, 0.72), (164, 58, 0.64), (191, 142, 0.85),
        (218, 198, 0.94), (245, 254, 0.98), (272, 184, 0.92), (299, 126, 0.84),
        (326, 162, 0.88), (353, 82, 0.70), (380, 44, 0.54),
    ]
    rects = []
    for x, h, op in bars:
        y = 256 - h / 2.0
        rects.append(f'<rect x="{x}" y="{y:.1f}" width="16.5" height="{h}" rx="8.25" fill="#FFFFFF" fill-opacity="{op}"/>')
    return _svg_wrap("\n".join(rects))


def svg_wallet():
    return _svg_wrap("""
    <rect x="122" y="130" width="268" height="82" rx="21" fill="#FFFFFF" fill-opacity="0.46"/>
    <rect x="122" y="160" width="268" height="82" rx="21" fill="#FFFFFF" fill-opacity="0.66"/>
    <rect x="122" y="191" width="268" height="82" rx="21" fill="#FFFFFF" fill-opacity="0.84"/>
    <path d="M108 230 L191 230 C202 230 210 238 216 249 C224 265 238 274 256 274 C274 274 288 265 296 249 C302 238 310 230 321 230 L404 230 L404 352 C404 373 387 390 366 390 L146 390 C125 390 108 373 108 352 Z"
          fill="#FFFFFF" fill-opacity="0.98"/>
    """)


def svg_key_attestation():
    return _svg_wrap("""
    <defs>
      <mask id="keyCut">
        <rect width="512" height="512" fill="white"/>
        <circle cx="178" cy="256" r="32" fill="black"/>
      </mask>
    </defs>
    <g mask="url(#keyCut)">
      <circle cx="178" cy="256" r="78" fill="#FFFFFF" fill-opacity="0.98"/>
      <rect x="235" y="234" width="178" height="44" rx="18" fill="#FFFFFF" fill-opacity="0.95"/>
      <rect x="330" y="267" width="30" height="50" rx="11" fill="#FFFFFF" fill-opacity="0.92"/>
      <rect x="374" y="267" width="30" height="39" rx="11" fill="#FFFFFF" fill-opacity="0.92"/>
    </g>
    """)


def svg_wallpapers():
    return _svg_wrap("""
    <defs>
      <mask id="palCut">
        <rect width="512" height="512" fill="white"/>
        <circle cx="179" cy="242" r="22" fill="black"/>
        <circle cx="227" cy="183" r="22" fill="black"/>
        <circle cx="298" cy="183" r="22" fill="black"/>
        <circle cx="340" cy="242" r="22" fill="black"/>
      </mask>
    </defs>
    <path d="M256 108 C172 108 104 175 104 256 C104 337 172 404 254 404 C280 404 298 386 298 364 C298 352 294 343 287 336 C281 329 278 323 278 316 C278 301 290 290 308 290 L340 290 C378 290 408 262 408 225 C408 159 339 108 256 108 Z"
          fill="#FFFFFF" fill-opacity="0.98" mask="url(#palCut)"/>
    """)


def svg_coloros_android():
    # Clean stock Android bugdroid head (removes ugly double-framed grid from ColorOS Glass Player default icon)
    return _svg_wrap("""
    <defs>
      <mask id="bugEyes">
        <rect width="512" height="512" fill="white"/>
        <circle cx="198" cy="272" r="16" fill="black"/>
        <circle cx="314" cy="272" r="16" fill="black"/>
      </mask>
    </defs>
    <line x1="176" y1="142" x2="206" y2="196" stroke="#FFFFFF" stroke-width="18" stroke-linecap="round" stroke-opacity="0.96"/>
    <line x1="336" y1="142" x2="306" y2="196" stroke="#FFFFFF" stroke-width="18" stroke-linecap="round" stroke-opacity="0.96"/>
    <path d="M114 334 C114 236 177 174 256 174 C335 174 398 236 398 334 C398 346 388 356 376 356 L136 356 C124 356 114 346 114 334 Z"
          fill="#FFFFFF" fill-opacity="0.98" mask="url(#bugEyes)"/>
    """)


# Map package name -> bespoke 1:1 iOS 26 Clear SVG generator function
# Note: Google Photos & Apple Music intentionally use their own official stock extracted _mono.png!
BESPOKE_PACKAGE_SVGS = {
    "com.google.android.apps.tachyon": ("ios_facetime", svg_facetime),
    "com.sec.android.gallery3d": ("ios_photos", svg_photos),
    "com.samsung.android.calendar": ("ios_calendar", svg_calendar),
    "com.sec.android.app.camera": ("ios_camera", svg_camera),
    "com.samsung.android.app.notes": ("ios_notes", svg_notes),
    "com.samsung.android.app.find": ("ios_findmy", svg_findmy),
    "com.sec.android.app.clockpackage": ("ios_clock", svg_clock),
    "com.google.android.apps.maps": ("ios_maps", svg_maps_layered),
    "com.google.android.apps.chromecast.app": ("ios_home", svg_home),
    "com.sec.android.app.samsungapps": ("ios_samsung_store", svg_samsung_store),
    "com.google.android.gm": ("ios_mail", svg_mail),
    "com.sec.android.app.myfiles": ("ios_files", svg_files),
    "com.samsung.android.dialer": ("ios_phone", svg_phone),
    "com.sec.android.app.sbrowser": ("ios_safari", svg_safari),
    "com.samsung.android.messaging": ("ios_messages", svg_messages),
    "com.google.android.apps.messaging": ("ios_google_messages", svg_messages),
    "com.android.settings": ("ios_settings", svg_settings),
    "com.sec.android.app.popupcalculator": ("ios_calculator", svg_calculator),
    "com.samsung.android.app.contacts": ("ios_contacts", svg_contacts),
    "com.sec.android.app.voicenote": ("ios_voicenote", svg_voicenote),
    "com.google.android.apps.walletnfcrel": ("ios_wallet", svg_wallet),
    "com.samsung.android.spay": ("ios_samsung_wallet", svg_wallet),
    "com.amazon.mShop.android.shopping": ("ios_amazon", svg_amazon),
    "io.github.vvb2060.keyattestation": ("ios_key_attestation", svg_key_attestation),
    "com.google.android.apps.wallpaper": ("ios_wallpapers", svg_wallpapers),
    "com.coloros.glassplayer": ("ios_glassplayer", svg_coloros_android),
}

BONUS_SVGS = {
    "ios_health": svg_health,
}
