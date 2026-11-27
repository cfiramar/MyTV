#!/usr/bin/env python3
"""
Curates v4 of SoarTV-launcher/app/assets/saver_catalog.json:
- Preserves exact <cat>_001 starter entries aligned with app/assets/saver_starters/<cat>.jpg.
- Purges all cross-category contaminated items (e.g. paintings/maps/buildings/vehicles/shells/wildlife in nature;
  cars/ponies/politicians/buildings in space; paintings/graves/buildings/1cm3-cubes in macro;
  war prints/natural pools in urban; autopsy/maps/book covers/museum rooms in paintings;
  politicians/gasometers/buildings/fountains/duplicate Great Waves in art).
- Cleans bilingual prefixes ("Dutch: ...", "German: ..."), Wiki markup ("by User:...", "( talk · contribs )"),
  scientific-binomial fake locations, and archival scan codes.
- Replenishes every category to exactly 205 verified True-4K (w >= 3840, h >= 2160, 1.25 <= w/h <= 2.20)
  unique landscape JPEGs from category-pure Wikimedia Commons Featured/Quality galleries.
"""

from __future__ import annotations

import html
import json
import math
import os
import re
import time
import urllib.parse
import urllib.request
from typing import Dict, List, Optional, Set, Tuple

CATALOG_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app",
    "assets",
    "saver_catalog.json",
)

CORE_CATEGORIES = ["nature", "urban", "macro", "space", "paintings", "art"]
CAT_PREFIX = {
    "nature": "nat",
    "urban": "urb",
    "macro": "mac",
    "space": "spa",
    "paintings": "pai",
    "art": "art",
}
TARGET_PER_CATEGORY = 205
USER_AGENT = "SoarTV-CatalogCurator/4.0 (Android TV Open-Gallery Curation)"

# Explicit contaminated IDs identified during full 1,230-item manual audit
EXPLICIT_DROP_IDS: Set[str] = {
    # NATURE contaminations: Hokusai Great Wave, maps, Rushmore, waterslide, trains, studio shells,
    # churches/cathedrals/castles/palaces/city halls/hotels/museums/schools, ships, helicopters, statues,
    # indoor lamp shop, human portrait, paintings/frescoes, bridges, close-up wildlife/animals
    "nat_005", "nat_008", "nat_020", "nat_022", "nat_029", "nat_030",
    "nat_038", "nat_039", "nat_040", "nat_043", "nat_044", "nat_046",
    "nat_049", "nat_051", "nat_059", "nat_061", "nat_062", "nat_067",
    "nat_069", "nat_070", "nat_076", "nat_082", "nat_088", "nat_095",
    "nat_099", "nat_102", "nat_103", "nat_104", "nat_105", "nat_107",
    "nat_108", "nat_109", "nat_111", "nat_112", "nat_113", "nat_114",
    "nat_115", "nat_118", "nat_119", "nat_120", "nat_124", "nat_125",
    "nat_132", "nat_134", "nat_141", "nat_145", "nat_146", "nat_147",
    "nat_149", "nat_151", "nat_152", "nat_164", "nat_172", "nat_175",
    "nat_176", "nat_177", "nat_178", "nat_183", "nat_184", "nat_185",
    "nat_187", "nat_188", "nat_189", "nat_196", "nat_197", "nat_199",
    "nat_200", "nat_202", "nat_203", "nat_204", "nat_205",
    # URBAN contaminations: video credit title, war woodblock print, natural pools, nuclear plant, rainforest
    "urb_015", "urb_022", "urb_068", "urb_128", "urb_129",
    # MACRO contaminations: Van Gogh Irises, 1cm3 lab metal cubes, wedding/exhibition/buildings/statue/grave/wide parks
    "mac_019", "mac_023", "mac_029", "mac_030", "mac_031", "mac_032",
    "mac_033", "mac_037", "mac_041", "mac_068", "mac_098", "mac_114",
    "mac_117", "mac_140", "mac_149", "mac_163", "mac_165", "mac_169",
    "mac_182", "mac_186", "mac_193",
    # SPACE contaminations: Christ the Redeemer, Mykonos, basilica, ponies, minister visit, BMW car, tourists, flowers, lab hardware
    "spa_037", "spa_038", "spa_039", "spa_047", "spa_162", "spa_191",
    "spa_199", "spa_202", "spa_203",
    # PAINTINGS contaminations: book cover, museum rooms, maps, costume sketch, Autopsy, ceiling fragment, scrapbooks, manuscript, stone head, diagram
    "pai_019", "pai_030", "pai_076", "pai_079", "pai_093", "pai_095",
    "pai_110", "pai_111", "pai_137", "pai_147", "pai_164", "pai_171",
    "pai_179", "pai_181",
    # ART contaminations: street fountains, Supreme Court, museum rooms/staircases, catalog cover, politician, gasometer, duplicate Great Waves, raw proof scans
    "art_011", "art_013", "art_019", "art_020", "art_026", "art_029",
    "art_030", "art_031", "art_033", "art_050", "art_080", "art_118",
    "art_158", "art_185", "art_188", "art_189", "art_190", "art_191",
    "art_194", "art_197", "art_200",
}

# Strict per-category keyword banlists (checked case-insensitively against title + url + artist)
GLOBAL_BAN_PATTERNS = [
    r"\bautopsy\b", r"\bcorpse\b", r"\bcemetery\b", r"\bgravestone\b", r"\btombstone\b",
    r"\bslaughter\b", r"\bnazi\b", r"\bexecution\b", r"\bmassacre\b", r"\bterrorist\b",
    r"\bpolitician\b", r"\bminister\b", r"\bsenator\b", r"\bcongressman\b", r"\brepresentative\b",
    r"\bscreenshot\b", r"\bdiagram\b", r"\bchart\b", r"\binfographic\b", r"\bbarcode\b",
    r"\bqr\s*code\b", r"\blogo\b", r"\bcoat_of_arms\b", r"\bpostage_stamp\b",
    r"\b1cm3\b", r"\bcube_99\b", r"\bnuclear_power\b", r"\bgasometer\b",
]

CATEGORY_BAN_PATTERNS: Dict[str, List[str]] = {
    "nature": [
        r"\bhokusai\b", r"\bkanagawa\b", r"\bpainting\b", r"\bfresco\b", r"\blithograph\b",
        r"\bwoodblock\b", r"\bukiyo\b", r"\bcanvas\b", r"\bwatercolor\b", r"\bengaving\b",
        r"\bengraving\b", r"\betching\b", r"\batlas\b", r"\bmap_of\b", r"\bmap\b",
        r"\bcathedral\b", r"\bchurch\b", r"\bbasilica\b", r"\bchapel\b", r"\bmosque\b",
        r"\bsynagogue\b", r"\btemple\b", r"\bmonastery\b", r"\babbey\b",
        r"\bcastle\b", r"\bpalace\b", r"\bfortress\b", r"\bschloss\b", r"\bchateau\b", r"\bchâteau\b",
        r"\bcity_hall\b", r"\brathaus\b", r"\bhotel\b", r"\bmuseum\b", r"\bschool\b",
        r"\buniversity\b", r"\bparliament\b", r"\bskyscraper\b", r"\bairport\b", r"\bstadium\b",
        r"\bhelicopter\b", r"\bpave_hawk\b", r"\baircraft\b", r"\bairplane\b", r"\bfighter_jet\b",
        r"\bwarship\b", r"\bbattleship\b", r"\bcruise_ship\b", r"\bfreighter\b",
        r"\blocomotive\b", r"\btrain\b", r"\brailway\b", r"\btram\b", r"\bwaterslide\b",
        r"\bautomobile\b", r"\bmotorcycle\b", r"\btractor\b",
        r"\bstatue\b", r"\bsculpture\b", r"\bmonument\b", r"\brushmore\b", r"\bgormley\b",
        r"\bportrait_of\b", r"\bself-portrait\b", r"\bvintage_smile\b",
        r"zell\.jpg", r"\bbivalvia\b", r"\bgastropoda\b", r"\bmollusca\b", r"\bconus_\b", r"\bcypraea\b",
        r"\bpanthera\b", r"\btiger\b", r"\blion\b", r"\belephant\b", r"\brhinoceros\b",
        r"\bspider\b", r"\bscorpion\b", r"\bcockroach\b", r"\bmosquito\b", r"\bfly_on\b",
    ],
    "urban": [
        r"\bwoodblock\b", r"\blithograph\b", r"\bpainting\b", r"\bukiyo\b", r"\bfresco\b",
        r"\brainforest\b", r"\bnuclear\b", r"\binsect\b", r"\bspider\b", r"\bbutterfly\b",
    ],
    "macro": [
        r"\bvan_gogh\b", r"\bpainting\b", r"\boil_on_canvas\b", r"\bfresco\b", r"\bwoodblock\b",
        r"\b1cm3\b", r"\bwedding\b", r"\bexhibition\b", r"\boffice_building\b", r"\bskyscraper\b",
        r"\bcathedral\b", r"\bchurch\b", r"\bstadium\b", r"\bgrave\b", r"\bstatue\b",
        r"\bpanorama_of\b", r"\bskyline\b", r"\bcityscape\b",
    ],
    "space": [
        r"\bbmw\b", r"\bmercedes\b", r"\bferrari\b", r"\bporsche\b", r"\bcoupé\b", r"\bcoupe\b",
        r"\bsedan\b", r"\bsuv\b", r"\bpony\b", r"\bponies\b", r"\bhorse\b", r"\bcattle\b",
        r"\bchrist_the_redeemer\b", r"\bcorcovado\b", r"\bbasilica\b", r"\bcathedral\b",
        r"\bchurch\b", r"\bminister\b", r"\bpresident\b", r"\btourist\b", r"\bmykonos\b",
        r"\bcleanroom\b", r"\bconference\b", r"\bpress_release\b", r"\bportrait_of\b",
    ],
    "paintings": [
        r"\bmap_of\b", r"\bnps_map\b", r"\batlas\b", r"\bscrapbook\b", r"\bbook_cover\b",
        r"\bmanuscript_page\b", r"\bphotograph_of_room\b", r"\bgallery_view\b", r"\bmuseum_interior\b",
        r"\bstone_head\b", r"\bmarble_bust\b", r"\bbronze_statue\b", r"\bfloor_plan\b",
    ],
    "art": [
        r"\bgasometer\b", r"\bsupreme_court\b", r"\bcity_hall\b", r"\boffice_building\b",
        r"\bstaircase_in\b", r"\bmuseum_room\b", r"\bgallery_interior\b", r"\bpress_photo\b",
        r"\bcatalog_cover\b", r"\bbook_cover\b", r"\btraffic\b", r"\bhighway\b",
    ],
}

# Category-pure Wikimedia Commons discovery feeds
DISCOVERY_FEEDS: Dict[str, List[str]] = {
    "nature": [
        "Category:Featured_pictures_of_landscapes",
        "Category:Featured_pictures_of_mountains",
        "Category:Featured_pictures_of_lakes",
        "Category:Featured_pictures_of_waterfalls",
        "Category:Featured_pictures_of_coasts",
        "Category:Featured_pictures_of_trees",
        "Category:Featured_pictures_of_rivers",
        "Category:Featured_pictures_of_valleys",
        "Category:Featured_pictures_of_glaciers",
        "Category:Featured_pictures_of_deserts",
        "Category:Featured_pictures_of_clouds",
        "Category:Featured_pictures_of_auroras",
        "Category:Featured_pictures_of_Switzerland",
        "Category:Featured_pictures_of_Norway",
        "Category:Featured_pictures_of_Iceland",
        "Category:Featured_pictures_of_New_Zealand",
    ],
    "urban": [
        "Category:Featured_pictures_of_cityscapes",
        "Category:Featured_night_shots",
        "Category:Featured_pictures_of_bridges",
        "Category:Featured_pictures_of_skyscrapers",
        "Category:Featured_pictures_of_towers",
        "Category:Featured_pictures_of_Tokyo",
        "Category:Featured_pictures_of_New_York_City",
        "Category:Featured_pictures_of_Paris",
        "Category:Featured_pictures_of_London",
        "Category:Featured_pictures_of_Singapore",
    ],
    "macro": [
        "Category:Featured_pictures_of_flowers",
        "Category:Featured_pictures_of_butterflies",
        "Category:Featured_pictures_of_snowflakes",
        "Category:Featured_pictures_of_dragonflies",
        "Category:Featured_pictures_of_fungi",
        "Category:Featured_pictures_of_minerals",
        "Category:Featured_pictures_of_leaves",
        "Category:Quality_images_of_macro_photography",
        "Category:Quality_images_of_water_droplets",
        "Category:Quality_images_of_flowers",
    ],
    "space": [
        "Category:Featured_pictures_of_astronomy",
        "Category:James_Webb_Space_Telescope_images",
        "Category:Featured_pictures_of_nebulae",
        "Category:Featured_pictures_of_galaxies",
        "Category:Featured_pictures_of_star_clusters",
        "Category:Featured_pictures_of_the_Milky_Way",
        "Category:ESO_Featured_Pictures",
        "Category:Hubble_Space_Telescope_images_of_nebulae",
        "Category:Hubble_Space_Telescope_images_of_galaxies",
    ],
    "paintings": [
        "Category:Featured_pictures_of_paintings",
        "Category:Featured_pictures_of_landscape_paintings",
        "Category:Google_Art_Project_works_by_Claude_Monet",
        "Category:Google_Art_Project_works_by_Vincent_van_Gogh",
        "Category:Google_Art_Project_works_by_Albert_Bierstadt",
        "Category:Google_Art_Project_works_by_Ivan_Aivazovsky",
        "Category:Google_Art_Project_works_by_J._M._W._Turner",
        "Category:Google_Art_Project_works_by_Camille_Pissarro",
        "Category:Google_Art_Project_works_by_Paul_Cézanne",
        "Category:Paintings_in_the_National_Gallery_of_Art",
        "Category:Landscape_paintings_in_the_Metropolitan_Museum_of_Art",
    ],
    "art": [
        "Category:Featured_pictures_of_stained_glass",
        "Category:Featured_pictures_of_mosaics",
        "Category:Featured_pictures_of_sculptures",
        "Category:Featured_pictures_of_Japanese_art",
        "Category:Thirty-six_Views_of_Mount_Fuji",
        "Category:Featured_pictures_of_tapestries",
        "Category:Featured_pictures_of_ceramics",
        "Category:Featured_pictures_of_reliefs",
        "Category:Featured_pictures_of_ceilings",
        "Category:Featured_pictures_of_decorative_arts",
    ],
}


def is_true_4k(w: int, h: int) -> bool:
    if w < 3840 or h < 2160 or w <= h:
        return False
    ratio = w / float(h)
    return 1.25 <= ratio <= 2.20


def strip_html_and_entities(raw: str) -> str:
    if not raw:
        return ""
    s = re.sub(r"<[^>]+>", " ", raw)
    s = html.unescape(s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


LANG_LABEL_RE = re.compile(
    r"\b(?:English|Dutch|German|French|Italian|Spanish|Portuguese|Russian|Japanese|Chinese|Latin|Polish|Swedish|Czech|Hungarian|Danish|Norwegian|Catalan|Nederlands|Deutsch|Français|Italiano|Español)\s*:\s*",
    re.IGNORECASE,
)


def clean_title(raw: str) -> str:
    if not raw:
        return ""
    s = strip_html_and_entities(raw)

    # Extract English segment if bilingual labels exist (e.g. "Dutch: Sterrennacht English: The Starry Night")
    m_en = re.search(
        r"\bEnglish\s*:\s*(.+?)(?=\b(?:Dutch|German|French|Italian|Spanish|Portuguese|Russian|Japanese|Chinese|Latin|Polish|Swedish|Czech|Nederlands|Deutsch|Français)\s*:|$)",
        s,
        re.IGNORECASE,
    )
    if m_en:
        s = m_en.group(1).strip()
    else:
        # Remove leading language label if only one language tag is present
        s = LANG_LABEL_RE.sub("", s).strip()

    # Remove archival / camera / Wikimedia noise
    s = re.sub(r"^File\s*:\s*", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\.(?:jpe?g|png|tiff?|webp)$", "", s, flags=re.IGNORECASE)
    s = re.sub(r"^proof\s+of\s+", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\bby\s+User\s*:[^\.,;|\-]+", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\bUser\s*:[^\.,;|\-]+", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\(\s*talk\s*[·|]\s*contribs\s*\)", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\bLCCN\d+[a-z0-9\-]*\b", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\bGoogle\s+Art\s+Project\b", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\b16x9-R[_\s]*\d*K?\b", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\b(?:DSC|IMG|DJI|PANO|RAW)[_\-]?\d{3,}\b", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\d{2}\s+[A-Z0-9]{3,}\b", "", s)
    s = re.sub(r"[_\s]+\d{6,8}$", "", s)
    s = re.sub(r"\s*[-–—]\s*(?:Wikimedia\s+Commons|featured\s+picture).*$", "", s, flags=re.IGNORECASE)
    s = s.replace("_", " ")
    # Strip trailing parenthetical numeric IDs like "(52341)" or "(01)"
    s = re.sub(r"\s*\((?:\d{1,7}|#[0-9a-zA-Z\-]+)\)\s*$", "", s)
    # Strip empty parens or dangling punctuation
    s = re.sub(r"\(\s*\)", "", s)
    s = re.sub(r"\s+", " ", s).strip(" -–—·,;:.")
    if len(s) > 88:
        cut = s[:85].rsplit(" ", 1)[0]
        s = (cut if len(cut) >= 24 else s[:85]).rstrip(" -–—·,;:.")
    return s


def clean_artist(raw: str) -> str:
    if not raw:
        return ""
    s = strip_html_and_entities(raw)
    s = re.sub(r"^(?:Photo(?:graph)?(?:\s+by)?|Created\s+by|Author|Artist|Credit|By)\s*:\s*", "", s, flags=re.IGNORECASE)
    s = re.sub(r"^by\s+", "", s, flags=re.IGNORECASE)
    s = re.sub(r"^User\s*:\s*", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\(\s*talk\s*[·|]\s*contribs\s*\)", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\bOwn\s+work\b", "", s, flags=re.IGNORECASE)
    # Remove life dates like "(1853–1890)" or "(born 1975)"
    s = re.sub(r"\s*\((?:c\.\s*)?\d{4}\s*[-–—]\s*\d{4}\)", "", s)
    s = re.sub(r"\s*\((?:born|b\.|d\.|died|active)\s+[^)]+\)", "", s, flags=re.IGNORECASE)
    s = LANG_LABEL_RE.sub("", s)
    # Take first primary segment if noisy multi-clause credit
    for sep in [" | ", " — ", " - Own work", "; "]:
        if sep in s:
            s = s.split(sep, 1)[0]
    s = re.sub(r"\(\s*\)", "", s)
    s = re.sub(r"\s+", " ", s).strip(" -–—·,;:./")
    if s.lower() in {"unknown", "unknown author", "unknown artist", "anonymous", "n/a", "none", "unidentified"}:
        return ""
    if re.search(r"https?://|commons\.wikimedia", s, re.IGNORECASE):
        return ""
    if len(s) > 56:
        s = s[:54].rsplit(" ", 1)[0].rstrip(" -–—·,;:./")
    return s


SCIENTIFIC_LOC_RE = re.compile(r"\b[A-Z][a-z]+us\s+[a-z]{4,}\b")


def clean_location(raw: str, category: str) -> str:
    if category in {"space", "macro", "paintings", "art"}:
        return ""
    if not raw:
        return ""
    s = strip_html_and_entities(raw)
    if SCIENTIFIC_LOC_RE.search(s):
        return ""
    if re.search(r"\b(?:User:|Own work|http|JPG| camera |mm f/)", s, re.IGNORECASE):
        return ""
    s = re.sub(r"\(\s*\)", "", s)
    s = re.sub(r"\s+", " ", s).strip(" -–—·,;:.")
    if len(s) > 64:
        s = s[:62].rsplit(",", 1)[0].strip()
    return s


def is_entry_pure_for_category(cat: str, title: str, url: str, artist: str) -> bool:
    blob = f"{title} {urllib.parse.unquote(url)} {artist}".lower()
    for pat in GLOBAL_BAN_PATTERNS:
        if re.search(pat, blob):
            return False
    for pat in CATEGORY_BAN_PATTERNS.get(cat, []):
        if re.search(pat, blob):
            return False
    if not title or len(title) < 4:
        return False
    # Reject numeric-only or raw filename-code titles
    if re.match(r"^[\d\W_]+$", title):
        return False
    return True


def canonical_url_key(url: str) -> str:
    u = urllib.parse.unquote(url.strip())
    # Normalize Wikimedia thumb vs direct filename to prevent duplicate images at different resolutions
    m = re.search(r"/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/?]+)", u)
    if m:
        return "wm:" + m.group(1).lower()
    return u.split("?")[0].lower()


def fetch_wikimedia_candidates(cat: str, needed: int, seen_keys: Set[str]) -> List[dict]:
    results: List[dict] = []
    feeds = DISCOVERY_FEEDS.get(cat, [])
    for feed in feeds:
        if len(results) >= needed:
            break
        gcmcontinue: Optional[str] = None
        pages_fetched = 0
        while len(results) < needed and pages_fetched < 5:
            pages_fetched += 1
            params = {
                "action": "query",
                "generator": "categorymembers",
                "gcmtitle": feed,
                "gcmtype": "file",
                "gcmlimit": "50",
                "prop": "imageinfo",
                "iiprop": "url|size|mime|extmetadata",
                "iiurlwidth": "3840",
                "format": "json",
            }
            if gcmcontinue:
                params["gcmcontinue"] = gcmcontinue
            api_url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
            req = urllib.request.Request(api_url, headers={"User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
            except Exception as exc:
                print(f"  [WARN] {feed} fetch error: {exc}")
                time.sleep(0.5)
                break

            pages = payload.get("query", {}).get("pages", {})
            for _, page in pages.items():
                if len(results) >= needed:
                    break
                infos = page.get("imageinfo") or []
                if not infos:
                    continue
                info = infos[0]
                mime = (info.get("mime") or "").lower()
                if mime not in {"image/jpeg", "image/jpg"}:
                    continue
                orig_w = int(info.get("width") or 0)
                orig_h = int(info.get("height") or 0)
                orig_size = int(info.get("size") or 0)
                if not is_true_4k(orig_w, orig_h):
                    continue

                # Choose optimal 4K URL:
                # 1) If original JPEG is already compact 4K..6K (<= 16 MB), direct static CDN URL avoids Thumbor rate-limits AND has exact orig_w x orig_h >= 3840x2160!
                # 2) Otherwise use 3840px thumb ONLY if thumbheight >= 2160 (is_true_4k(thumb_w, thumb_h)).
                thumb_url = (info.get("thumburl") or "").split("?")[0].replace(
                    "https://thumb.wikimedia.org/", "https://upload.wikimedia.org/"
                )
                thumb_w = int(info.get("thumbwidth") or 0)
                thumb_h = int(info.get("thumbheight") or 0)
                orig_url = (info.get("url") or "").split("?")[0]

                chosen_url = ""
                chosen_w, chosen_h = 0, 0
                if orig_url.lower().endswith((".jpg", ".jpeg")) and 40_000 <= orig_size <= 16_500_000:
                    chosen_url = orig_url
                    chosen_w, chosen_h = orig_w, orig_h
                elif thumb_url.lower().endswith((".jpg", ".jpeg")) and is_true_4k(thumb_w, thumb_h):
                    chosen_url = thumb_url
                    chosen_w, chosen_h = thumb_w, thumb_h
                else:
                    continue

                ukey = canonical_url_key(chosen_url)
                if ukey in seen_keys:
                    continue

                ext = info.get("extmetadata") or {}
                raw_obj_name = (ext.get("ObjectName") or {}).get("value") or ""
                raw_desc = (ext.get("ImageDescription") or {}).get("value") or ""
                fallback_file_title = re.sub(r"^File:", "", page.get("title") or "")
                title = clean_title(raw_obj_name) or clean_title(fallback_file_title) or clean_title(raw_desc)
                artist = clean_artist((ext.get("Artist") or {}).get("value") or "")

                # Prevent duplicate Hokusai Great Wave in art
                if "kanagawa" in (title + " " + chosen_url).lower():
                    continue

                if not is_entry_pure_for_category(cat, title, chosen_url, artist):
                    continue

                loc = ""
                if cat in {"nature", "urban"}:
                    # Try extracting clean location from Categories or ObjectName if available
                    loc_candidate = (ext.get("LocationDest") or {}).get("value") or ""
                    loc = clean_location(loc_candidate, cat)

                seen_keys.add(ukey)
                results.append(
                    {
                        "cat": cat,
                        "category": cat,
                        "title": title,
                        "artist": artist,
                        "location": loc,
                        "building": "",
                        "url": chosen_url,
                        "imageUrl": chosen_url,
                        "w": chosen_w,
                        "h": chosen_h,
                    }
                )

            cont = payload.get("continue") or {}
            gcmcontinue = cont.get("gcmcontinue")
            time.sleep(0.25)
            if not gcmcontinue:
                break
    return results


def main() -> None:
    with open(CATALOG_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    raw_items: List[dict] = data.get("items", [])
    seen_keys: Set[str] = set()
    kept_by_cat: Dict[str, List[dict]] = {c: [] for c in CORE_CATEGORIES}
    kanagawa_seen = False

    # Pass 1: Keep all 6 <cat>_001 starter entries FIRST so starter JPEGs always align 1:1
    for it in raw_items:
        cid = it.get("id", "")
        cat = (it.get("category") or it.get("cat") or "").lower()
        if cid.endswith("_001") and cat in kept_by_cat:
            url = it.get("imageUrl") or it.get("url") or ""
            ukey = canonical_url_key(url)
            seen_keys.add(ukey)
            if "kanagawa" in (it.get("title", "") + " " + url).lower():
                kanagawa_seen = True
            it["title"] = clean_title(it.get("title", "")) or it.get("title", "")
            it["artist"] = clean_artist(it.get("artist", "")) or it.get("artist", "")
            it["location"] = clean_location(it.get("location", ""), cat)
            kept_by_cat[cat].append(it)

    # Pass 2: Filter remaining existing items strictly
    dropped_counts = {c: 0 for c in CORE_CATEGORIES}
    for it in raw_items:
        cid = it.get("id", "")
        if cid.endswith("_001"):
            continue
        cat = (it.get("category") or it.get("cat") or "").lower()
        if cat not in kept_by_cat:
            continue
        if cid in EXPLICIT_DROP_IDS:
            dropped_counts[cat] += 1
            continue

        url = it.get("imageUrl") or it.get("url") or ""
        w = int(it.get("w") or 0)
        h = int(it.get("h") or 0)
        if not url.startswith("https://") or not is_true_4k(w, h):
            dropped_counts[cat] += 1
            continue

        title = clean_title(it.get("title", ""))
        artist = clean_artist(it.get("artist", ""))
        loc = clean_location(it.get("location", ""), cat)
        bld = strip_html_and_entities(it.get("building", "")) if cat == "urban" else ""

        if "kanagawa" in (title + " " + url).lower():
            if kanagawa_seen or cat != "art":
                dropped_counts[cat] += 1
                continue
            kanagawa_seen = True

        if not is_entry_pure_for_category(cat, title, url, artist):
            dropped_counts[cat] += 1
            continue

        ukey = canonical_url_key(url)
        if ukey in seen_keys:
            dropped_counts[cat] += 1
            continue

        seen_keys.add(ukey)
        kept_by_cat[cat].append(
            {
                "cat": cat,
                "category": cat,
                "title": title,
                "artist": artist,
                "location": loc,
                "building": bld,
                "url": url,
                "imageUrl": url,
                "w": w,
                "h": h,
            }
        )

    for c in CORE_CATEGORIES:
        print(f"[{c}] kept={len(kept_by_cat[c])}, dropped={dropped_counts[c]}, need={max(0, TARGET_PER_CATEGORY - len(kept_by_cat[c]))}")

    # Pass 3: Replenish every category to TARGET_PER_CATEGORY (205) with pure 4K Wikimedia entries
    final_items: List[dict] = []
    for c in CORE_CATEGORIES:
        cur = kept_by_cat[c]
        if len(cur) < TARGET_PER_CATEGORY:
            need = TARGET_PER_CATEGORY - len(cur)
            print(f"Fetching {need} pure 4K replacements for '{c}'...")
            added = fetch_wikimedia_candidates(c, need + 10, seen_keys)
            cur.extend(added[:need])
            print(f"  -> '{c}' now has {len(cur)} entries")
        cur = cur[:TARGET_PER_CATEGORY]
        assert len(cur) == TARGET_PER_CATEGORY, f"Category {c} only reached {len(cur)}/{TARGET_PER_CATEGORY}"
        prefix = CAT_PREFIX[c]
        for idx, entry in enumerate(cur, 1):
            new_id = f"{prefix}_{idx:03d}"
            final_items.append(
                {
                    "id": new_id,
                    "cat": c,
                    "category": c,
                    "title": entry["title"],
                    "artist": entry["artist"],
                    "location": entry.get("location", ""),
                    "building": entry.get("building", ""),
                    "url": entry["imageUrl"],
                    "imageUrl": entry["imageUrl"],
                    "w": int(entry["w"]),
                    "h": int(entry["h"]),
                }
            )

    out_doc = {
        "version": 4,
        "curatedAt": "2026-11-06",
        "total": len(final_items),
        "categories": {c: TARGET_PER_CATEGORY for c in CORE_CATEGORIES},
        "items": final_items,
    }
    with open(CATALOG_PATH, "w", encoding="utf-8") as f:
        json.dump(out_doc, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"SUCCESS: Wrote {len(final_items)} pure True-4K entries across 6 categories to {CATALOG_PATH}")


if __name__ == "__main__":
    main()
