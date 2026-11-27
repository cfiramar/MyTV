#!/usr/bin/env python3
"""
Curated 4K UHD Photography & Fine Art Catalog Generator for SoarTV Screensaver.

Why:
- Produces >= 200 (205) museum- and editorial-grade True 4K UHD (>= 3840x2160 landscape)
  entries across each of the 6 core categories (nature, urban, macro, space, paintings, art = 1,230 total).
- Eliminates "free-slop" (raw camera filenames, HDR church photos misclassified as paintings,
  random buildings in art, star-trail test files, maps, scans, and blank locations).
- Prefers static Varnish/CDN original JPEGs (<= 9.5 MB) and official ESA/Webb 3840x2160 UHD wallpapers
  so TV downloads never trigger dynamic thumbnail rate-limits.
- Extracts authentic human-readable artwork titles, photographer/artist names, geographic locations,
  and specific architectural building/landmark names.
"""

import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

CATEGORIES = ["nature", "urban", "macro", "space", "paintings", "art"]
TARGET_PER_CATEGORY = 205
UA = "SoarTV/1.5 (Android TV 4K Screensaver Catalog Builder)"

# Hand-verified, iconic 3840x2160 True-4K lead masterpieces (index 001 of each category, also bundled as starter assets)
CURATED_LEADS = {
    "nature": [
        {
            "title": "Yosemite Valley Dawn & Merced River",
            "artist": "Bailey Zindel",
            "location": "Yosemite National Park, California",
            "building": "",
            "url": "https://images.unsplash.com/photo-1506744038136-46273834b3fb?fm=jpg&fit=crop&w=3840&h=2160&q=85",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "El Capitan & Cathedral Meadow",
            "artist": "Adam Kool",
            "location": "Yosemite National Park, California",
            "building": "",
            "url": "https://images.unsplash.com/photo-1426604966848-d7adac402bff?fm=jpg&fit=crop&w=3840&h=2160&q=85",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Morning Mist Through Spruce Forest",
            "artist": "Sebastian Unrau",
            "location": "Bad Pyrmont, Germany",
            "building": "",
            "url": "https://images.unsplash.com/photo-1448375240586-882707db888b?fm=jpg&fit=crop&w=3840&h=2160&q=85",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Alpine Lake & Dolomite Peaks at Sunrise",
            "artist": "Luca Bravo",
            "location": "Lago di Braies, Dolomites, Italy",
            "building": "",
            "url": "https://images.unsplash.com/photo-1470770841072-f978cf4d019e?fm=jpg&fit=crop&w=3840&h=2160&q=85",
            "w": 3840,
            "h": 2160,
        },
    ],
    "urban": [
        {
            "title": "Golden Gate Bridge Across the Marin Strait",
            "artist": "Maarten van den Heuvel",
            "location": "San Francisco, California",
            "building": "Golden Gate Bridge",
            "url": "https://images.unsplash.com/photo-1501594907352-04cda38ebc29?fm=jpg&fit=crop&w=3840&h=2160&q=85",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Grand Canal & Basilica di Santa Maria della Salute",
            "artist": "Henrique Ferreira",
            "location": "Venice, Italy",
            "building": "Basilica della Salute",
            "url": "https://images.unsplash.com/photo-1514890547357-a9ee288728e0?fm=jpg&fit=crop&w=3840&h=2160&q=85",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Manhattan Midtown Skyline at Dusk",
            "artist": "luca-bravo",
            "location": "New York City, USA",
            "building": "Empire State Building",
            "url": "https://images.unsplash.com/photo-1496442226666-8d4d0e62e6e9?fm=jpg&fit=crop&w=3840&h=2160&q=85",
            "w": 3840,
            "h": 2160,
        },
    ],
    "macro": [
        {
            "title": "Blue Passionflower (Passiflora caerulea)",
            "artist": "Petar Milošević",
            "location": "Ljubljana Botanical Garden, Slovenia",
            "building": "",
            "url": "https://upload.wikimedia.org/wikipedia/commons/3/30/Passiflora_caerulea_%28makro_close-up%29.jpg",
            "w": 4565,
            "h": 3245,
        },
    ],
    "space": [
        {
            "title": "Cosmic Cliffs in the Carina Nebula (NIRCam)",
            "artist": "NASA / ESA / CSA / STScI",
            "location": "",
            "building": "",
            "url": "https://cdn.esawebb.org/archives/images/wallpaper5/weic2205a.jpg",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Pillars of Creation in the Eagle Nebula",
            "artist": "NASA / ESA / CSA / STScI",
            "location": "",
            "building": "",
            "url": "https://cdn.esawebb.org/archives/images/wallpaper5/weic2216a.jpg",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Stephan's Quintet Galactic Grouping",
            "artist": "NASA / ESA / CSA / STScI",
            "location": "",
            "building": "",
            "url": "https://cdn.esawebb.org/archives/images/wallpaper5/weic2207b.jpg",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Tarantula Nebula Star-Forming Region",
            "artist": "NASA / ESA / CSA / STScI",
            "location": "",
            "building": "",
            "url": "https://cdn.esawebb.org/archives/images/wallpaper5/weic2212a.jpg",
            "w": 3840,
            "h": 2160,
        },
        {
            "title": "Cartwheel Galaxy & Companion Spiral Galaxies",
            "artist": "NASA / ESA / CSA / STScI",
            "location": "",
            "building": "",
            "url": "https://cdn.esawebb.org/archives/images/wallpaper5/weic2210a.jpg",
            "w": 3840,
            "h": 2160,
        },
    ],
    "paintings": [
        {
            "title": "Tulip Fields in Holland",
            "artist": "Claude Monet",
            "location": "",
            "building": "",
            "url": "https://upload.wikimedia.org/wikipedia/commons/b/b7/Claude_Monet_-_Champ_de_tulipes_en_Hollande.jpg",
            "w": 5198,
            "h": 3421,
        },
        {
            "title": "The Starry Night",
            "artist": "Vincent van Gogh",
            "location": "",
            "building": "",
            "url": "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ea/Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg/3840px-Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg",
            "w": 3840,
            "h": 3041,
        },
    ],
    "art": [
        {
            "title": "The Great Wave off Kanagawa",
            "artist": "Katsushika Hokusai",
            "location": "",
            "building": "",
            "url": "https://upload.wikimedia.org/wikipedia/commons/0/0a/The_Great_Wave_off_Kanagawa.jpg",
            "w": 4335,
            "h": 2990,
        },
    ],
}

CATEGORY_MEMBERS_FEEDS = {
    "nature": [
        "Category:Featured_pictures_of_landscapes",
        "Category:Featured_pictures_of_mountains",
        "Category:Featured_pictures_of_lakes",
        "Category:Featured_pictures_of_waterfalls",
        "Category:Featured_pictures_of_coasts",
        "Category:Featured_pictures_of_forests",
        "Category:Featured_pictures_of_glaciers",
    ],
    "urban": [
        "Category:Featured_pictures_of_cityscapes",
        "Category:Featured_night_shots",
        "Category:Featured_pictures_of_bridges",
        "Category:Featured_pictures_of_castles",
        "Category:Featured_pictures_of_palaces",
        "Category:Featured_pictures_of_skyscrapers",
    ],
    "macro": [
        "Category:Featured_pictures_of_flowers",
        "Category:Featured_pictures_of_butterflies",
        "Category:Featured_pictures_of_dragonflies",
        "Category:Featured_pictures_of_insects",
        "Category:Featured_pictures_of_fungi",
        "Category:Featured_pictures_of_minerals",
        "Category:Featured_pictures_of_arthropods",
    ],
    "space": [
        "Category:Featured_pictures_of_astronomy",
        "Category:Images_from_the_James_Webb_Space_Telescope",
        "Category:Hubble_Space_Telescope_images",
    ],
    "paintings": [
        "Category:Featured_pictures_of_paintings",
        "Category:Paintings_by_Claude_Monet",
        "Category:Paintings_by_Vincent_van_Gogh",
        "Category:Paintings_by_Albert_Bierstadt",
        "Category:Paintings_by_Ivan_Aivazovsky",
        "Category:Paintings_by_J._M._W._Turner",
        "Category:Paintings_by_Canaletto",
        "Category:Paintings_by_Thomas_Cole",
        "Category:Paintings_by_Pierre-Auguste_Renoir",
        "Category:Paintings_by_Paul_Cézanne",
        "Category:Paintings_by_Caspar_David_Friedrich",
        "Category:Paintings_by_Camille_Pissarro",
        "Category:Paintings_by_Alfred_Sisley",
        "Category:Paintings_by_Winslow_Homer",
        "Category:Paintings_by_Frederic_Edwin_Church",
        "Category:Paintings_by_Rembrandt",
        "Category:Paintings_by_Peter_Paul_Rubens",
        "Category:Paintings_by_Pieter_Bruegel_the_Elder",
    ],
    "art": [
        "Category:Featured_pictures_of_sculptures",
        "Category:Featured_pictures_of_stained_glass",
        "Category:Featured_pictures_of_mosaics",
        "Category:Thirty-six_Views_of_Mount_Fuji",
        "Category:Featured_pictures_of_works_of_art",
    ],
}

SEARCH_QUERIES = {
    "nature": [
        'deepcategory:"Featured_pictures_of_landscapes" filetype:bitmap filew:>3840 fileh:>2160',
        'incategory:"Commons_featured_desktop_backgrounds" (mountain OR lake OR fjord OR glacier OR valley OR canyon OR coast) filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_landscapes" (Dolomites OR Yosemite OR Lofoten OR Alps OR Iceland OR Patagonia OR Banff) filetype:bitmap filew:>3840 fileh:>2160',
    ],
    "urban": [
        'deepcategory:"Featured_pictures_of_cityscapes" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_cityscapes" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_skyscrapers" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_bridges" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_palaces" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_castles" filetype:bitmap filew:>3840 fileh:>2160',
    ],
    "macro": [
        'deepcategory:"Featured_pictures_of_flowers" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_flowers" (lotus OR orchid OR rose OR waterlily OR hibiscus OR dahlia OR iris OR tulip OR crocus OR anemone) filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Quality_images_of_flowers" filetype:bitmap filew:>3840 fileh:>2160',
        '(incategory:"Featured_pictures" OR incategory:"Quality_images") (butterfly OR dragonfly OR bumblebee OR ladybug OR quartz OR amethyst) filetype:bitmap filew:>3840 fileh:>2160',
    ],
    "space": [
        'deepcategory:"Featured_pictures_of_astronomy" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"European_Southern_Observatory_images" (nebula OR galaxy OR cluster OR Milky OR Carina OR Orion OR Centaurus OR Magellanic OR Alma OR Paranal) filetype:bitmap filew:>3840 fileh:>2160',
        '(nebula OR galaxy OR "star cluster" OR supernova OR "Pillars of Creation" OR "Andromeda") (Hubble OR Webb OR ESO OR NASA) filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"European_Southern_Observatory_images" filetype:bitmap filew:>3840 fileh:>2160',
    ],
    "paintings": [
        'deepcategory:"Featured_pictures_of_paintings" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Paintings_in_the_Metropolitan_Museum_of_Art" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Landscape_paintings" (Monet OR Gogh OR Renoir OR Turner OR Bierstadt OR Aivazovsky OR Canaletto OR Cezanne OR Pissarro OR Sisley OR Friedrich OR Cole OR Homer OR Church) filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Landscape_paintings" ("oil on canvas" OR museum) filetype:bitmap filew:>3840 fileh:>2160',
    ],
    "art": [
        'deepcategory:"Featured_pictures_of_stained_glass" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Featured_pictures_of_mosaics" filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Featured_pictures_of_sculptures" filetype:bitmap filew:>3840 fileh:>2160',
        '(Hokusai OR Hiroshige OR "Thirty-six Views of Mount Fuji" OR Ukiyo-e) filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Sculptures_in_museums" (Louvre OR Vatican OR Uffizi OR "Metropolitan Museum" OR "British Museum" OR marble OR bronze OR Bernini OR Canova OR Rodin OR Michelangelo) filetype:bitmap filew:>3840 fileh:>2160',
        'deepcategory:"Sculptures_in_museums" filetype:bitmap filew:>3840 fileh:>2160',
    ],
}

ICONIC_BUILDINGS = [
    "Marina Bay Sands", "Esplanade Bridge", "Eiffel Tower", "Louvre Pyramid", "Palais du Louvre",
    "Arc de Triomphe", "Notre-Dame", "Pont Alexandre III", "Pont de Bir-Hakeim", "Sacré-Cœur",
    "Mont-Saint-Michel", "Château de Chambord", "Château de Chenonceau", "Palace of Versailles",
    "Tower Bridge", "Palace of Westminster", "Big Ben", "St Paul's Cathedral", "Canary Wharf", "The Shard",
    "Colosseum", "Castel Sant'Angelo", "St. Peter's Basilica", "Pantheon", "Trevi Fountain",
    "Piazza San Marco", "Basilica della Salute", "Rialto Bridge", "Doge's Palace", "Duomo di Milano",
    "Florence Cathedral", "Ponte Vecchio", "Leaning Tower of Pisa", "Sagrada Família", "Alhambra",
    "Plaza de España", "Pena Palace", "Belém Tower", "Dom Luís I Bridge",
    "Neuschwanstein Castle", "Brandenburg Gate", "Reichstag", "Cologne Cathedral", "Elbphilharmonie",
    "Hohenzollern Castle", "Eltz Castle", "Schönbrunn Palace", "Hungarian Parliament Building",
    "Charles Bridge", "Prague Castle", "Rijksmuseum", "Acropolis", "Parthenon", "Hagia Sophia",
    "Blue Mosque", "Taj Mahal", "Amber Fort", "Hawa Mahal", "Burj Khalifa", "Sheikh Zayed Grand Mosque",
    "Petronas Towers", "Taipei 101", "Tokyo Tower", "Tokyo Skytree", "Himeji Castle", "Kinkaku-ji",
    "Fushimi Inari", "Senso-ji", "Osaka Castle", "Gyeongbokgung Palace", "Forbidden City",
    "Temple of Heaven", "Summer Palace", "Sydney Opera House", "Sydney Harbour Bridge",
    "Golden Gate Bridge", "Brooklyn Bridge", "Manhattan Bridge", "Empire State Building",
    "Chrysler Building", "One World Trade Center", "Flatiron Building", "Grand Central Terminal",
    "United States Capitol", "Lincoln Memorial", "Willis Tower", "Space Needle", "Château Frontenac",
]

COUNTRIES_AND_REGIONS = [
    "Italy", "Switzerland", "France", "Germany", "Austria", "Spain", "Portugal", "United Kingdom",
    "England", "Scotland", "Wales", "Ireland", "Iceland", "Norway", "Sweden", "Finland", "Denmark",
    "Netherlands", "Belgium", "Luxembourg", "Greece", "Croatia", "Slovenia", "Montenegro",
    "Czech Republic", "Poland", "Hungary", "Romania", "Estonia", "Latvia", "Lithuania", "Turkey",
    "United States", "California", "Utah", "Arizona", "Wyoming", "Montana", "Colorado", "Oregon",
    "Washington", "Alaska", "Hawaii", "New York", "Chicago", "San Francisco", "Boston",
    "Canada", "Alberta", "British Columbia", "Quebec", "Mexico", "Costa Rica", "Peru", "Chile",
    "Argentina", "Patagonia", "Brazil", "Bolivia", "Ecuador",
    "Japan", "Tokyo", "Kyoto", "China", "Hong Kong", "Taiwan", "South Korea", "Singapore",
    "Thailand", "Vietnam", "Indonesia", "Bali", "Malaysia", "India", "Nepal", "Sri Lanka",
    "United Arab Emirates", "Dubai", "Abu Dhabi", "Jordan", "Israel", "Morocco", "Egypt",
    "Namibia", "South Africa", "Kenya", "Tanzania", "Madagascar", "Australia", "New Zealand",
    "Dolomites", "South Tyrol", "Yosemite", "Lofoten", "Zermatt", "Venice", "Rome", "Paris", "London",
]

COUNTRY_CODES = {
    "LU": "Luxembourg", "DE": "Germany", "CH": "Switzerland", "AT": "Austria", "FR": "France",
    "IT": "Italy", "ES": "Spain", "PT": "Portugal", "UK": "United Kingdom", "NL": "Netherlands",
    "BE": "Belgium", "NO": "Norway", "SE": "Sweden", "FI": "Finland", "IS": "Iceland",
    "US": "USA", "USA": "USA", "CA": "Canada", "JP": "Japan", "SG": "Singapore",
}

SLOP_KEYWORDS = [
    "diagram", "map of", "chart", "graph", "logo", "coat of arms", "flag of", "plaque",
    "signboard", "gravestone", "tombstone", "document", "manuscript page", "newspaper",
    "postage stamp", "banknote", "coin ", "portrait of a man", "selfie", "press conference",
    "parking lot", "construction site", "bus station", "train platform", "supermarket",
    "historical society", "town hall annex", "wikimedia", "test image", "calibration",
]

PHOTO_CAMERA_MARKERS = [
    "taken with", "nikon", "canon eos", "sony ilce", "fujifilm", "panasonic dmc",
    "pentax", "olympus", "hasselblad", "dji_", "hdr", "dd_", "aerial photograph",
    "church interior", "cathedral interior", "ceiling of", "nave of",
]


def clean_html(s):
    if not s:
        return ""
    s = html.unescape(str(s))
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def sanitize_artist(raw):
    cleaned = clean_html(raw)
    if not cleaned:
        return ""
    # Handle wikimedia creator templates & redundant prefixes ("Credit:", "Photograph by")
    cleaned = re.sub(r"^(?:Creator\s*:\s*|Credit\s*:\s*|Photograph\s+by\s+|Photo\s+by\s+)+", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\.\s*Acknowledgement:.*$", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*\(talk\).*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*from\s+[^,]+,.*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*https?://\S+", "", cleaned)
    cleaned = re.sub(r"\s*\(\s*[^)]*(?:\.com|\.org|\.net|\.de)[^)]*\)\.?", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*\([0-9]{3,4}\s*[–\-]\s*[0-9]{3,4}\)", "", cleaned)
    cleaned = re.sub(r"\s*©.*", "", cleaned)
    cleaned = re.sub(r"\s*/\s*Wikimedia Commons.*", "", cleaned, flags=re.IGNORECASE)
    cleaned = cleaned.strip(" ,.;-–—|")
    if "\n" in cleaned:
        cleaned = cleaned.splitlines()[0].strip()
    if len(cleaned) > 46:
        # Take first segment before semicolon or parenthetical bio
        cleaned = re.split(r"[;|(]", cleaned)[0].strip()
    lower = cleaned.lower()
    if (
        len(cleaned) < 2
        or len(cleaned) > 48
        or "unknown" in lower
        or "wikimedia" in lower
        or "note:" in lower
        or "user:" in lower
        or "upload" in lower
        or "anonymous" in lower
    ):
        return ""
    return cleaned


def clean_artwork_and_artist(raw_title, fallback_file_title, artist, cat_key):
    t = clean_html(raw_title) or clean_html(fallback_file_title)
    if "QS:" in t:
        m_en = re.search(r'\ben:"([^"]+)"', t)
        if m_en:
            t = m_en.group(1)
        else:
            t = re.sub(r"\s*(?:label|title)\s+QS:.*$", "", t, flags=re.IGNORECASE)
            if not t.strip():
                t = clean_html(fallback_file_title)
    t = re.sub(r"^File:\s*", "", t, flags=re.IGNORECASE)
    t = re.sub(r"^[a-z]{2,3}:\s*", "", t)
    t = re.sub(r"\.[a-zA-Z0-9]{2,5}$", "", t)
    t = re.sub(r"[\s\-–_]*Google\s+Art\s+Project.*$", "", t, flags=re.IGNORECASE)
    t = re.sub(r"[\s\-–_]*WGA[0-9a-zA-Z_\-]*$", "", t)
    t = re.sub(r"\s+ESA[0-9]{4,}\b.*$", "", t)
    t = re.sub(r"\s*-\s*[A-Z][a-z]+[0-9]+\s*$", "", t)
    t = re.sub(r"[\s\-–_]*\([0-9]{4,}\)$", "", t)
    t = re.sub(r"\s*--\s*[0-9]{4}.*$", "", t)
    t = re.sub(r",?\s*[0-9]{4}-[0-9]{2}-[0-9]{2}.*$", "", t)
    t = re.sub(r",?\s*DD[\s_][0-9\-]+.*$", "", t)
    t = re.sub(r"\b(HDR|PANO|DSC_?[0-9]+|IMG_?[0-9]+|DJI_?[0-9]+)\b.*$", "", t, flags=re.IGNORECASE)
    t = re.sub(r"[\s\-]+IMG[\s_]*\d+.*$", "", t, flags=re.IGNORECASE)
    t = re.sub(r"\s*\((?:[^)]*(?:-cc|img|uhd|vlt|eso|potw|iotw|ann\d|d5[ac]|kh9a|agp\d|paranal|malin)[^)]*)\)\s*$", "", t, flags=re.IGNORECASE)
    t = re.sub(r",\s*(?:RP-[PT]-[0-9A-Z\-]+|AK-MAK-[0-9A-Z\-]+|RV-[0-9A-Z\-]+|MET\s+LC-[0-9A-Z\-\s]+).*$", "", t)
    t = re.sub(r"^[0-9]{2,4}[\-_][0-9]{2}[\-_][0-9]{2}[\-_\s]*", "", t)
    t = re.sub(r"\s+([0-9]{1,2}|G[0-9])$", "", t)
    t = t.replace("_", " ")
    t = re.sub(r"\s+", " ", t).strip(" ,;-–—\"'")

    # For Google Art Project / museum filenames formatted as "Artist - Title - Museum"
    if cat_key in ("paintings", "art") and " - " in t:
        parts = [p.strip() for p in t.split(" - ") if p.strip()]
        if len(parts) >= 2:
            maybe_artist, maybe_title = parts[0], parts[1]
            # Drop museum accession numbers like "1926.18" or "MET DP..."
            if not re.match(r"^(MET|NGA|WGA|[0-9])", maybe_title):
                if not artist and 3 <= len(maybe_artist) <= 40 and not any(ch.isdigit() for ch in maybe_artist):
                    artist = maybe_artist
                t = maybe_title

    # Trim catalogue suffixes like ", c. 1889" only if overly long
    if len(t) > 76:
        t = t[:76].rsplit(" ", 1)[0].strip(" ,;-–")
    return t, artist


def is_clean_human_title(t):
    if not t or len(t) < 5 or len(t) > 84:
        return False
    lower = t.lower()
    if re.match(r"^[0-9]{2,}[\-_]", t):
        return False
    if any(bad in lower for bad in ("file-etoiles", "dsc", "img_", "dji_", ".jpg", ".png", ".tif", "wikimedia", "russian museum", "palace of haji")):
        return False
    for kw in SLOP_KEYWORDS:
        if kw in lower:
            return False
    for ch in t:
        if ord(ch) > 0x024F and ch not in "’‘“”–—·":
            return False
    letters = sum(1 for c in t if ("A" <= c <= "Z") or ("a" <= c <= "z"))
    digits = sum(1 for c in t if c.isdigit())
    if letters < 5 or digits > letters * 0.45:
        return False
    return True


def extract_location(raw_file_title, desc, categories_str):
    # 1. Check explicit "(Country/State)" in raw title e.g. "Berdorf (LU)" or "Amboy (California, USA)"
    m_code = re.match(r"^([A-Z][A-Za-zÀ-ÿ\-\s]{2,28})\s*\(([A-Z]{2,3})\)", raw_file_title)
    if m_code:
        place = m_code.group(1).strip(" ,-")
        cc = m_code.group(2)
        if cc in COUNTRY_CODES:
            return f"{place}, {COUNTRY_CODES[cc]}"

    m_paren = re.match(r"^([A-Z][A-Za-zÀ-ÿ\-\s]{2,28})\s*\(([A-Za-z,\s]{4,32})\)", raw_file_title)
    if m_paren:
        place = m_paren.group(1).strip(" ,-")
        region = m_paren.group(2).strip(" ,-")
        if not any(ch.isdigit() for ch in region):
            return f"{place}, {region}"

    # 2. Check Wikimedia Categories for geographic patterns: "Landscapes of X", "Mountains of X", "Views of X", "Buildings in X", "... in City, Country"
    cats = [c.strip() for c in (categories_str or "").split("|") if c.strip()]
    geo_candidates = []
    for c in cats:
        m = re.search(
            r"^(?:Featured pictures of|Quality images of|Landscapes of|Mountains of|Lakes of|Waterfalls of|Rivers of|Coasts of|Castles in|Palaces in|Bridges in|Skyscrapers in|Buildings in|Architecture of|Night in|Views of|Nature of|National parks of)\s+(.+)$",
            c,
            flags=re.IGNORECASE,
        )
        if m:
            loc = m.group(1).strip()
            loc = re.sub(r"^the\s+", "", loc, flags=re.IGNORECASE)
            if 3 <= len(loc) <= 42 and not any(w in loc.lower() for w in ("landscapes", "architecture", "nature", "buildings", "night", "winter", "autumn", "spring", "summer", "clouds", "trees", "water")):
                geo_candidates.append(loc)

    # 3. Match known countries/regions in categories + description + title
    combined = f"{categories_str} | {desc} | {raw_file_title}"
    matched_places = []
    for place in COUNTRIES_AND_REGIONS:
        if re.search(r"\b" + re.escape(place) + r"\b", combined):
            if place not in matched_places:
                matched_places.append(place)

    if geo_candidates:
        best = geo_candidates[0]
        for mp in matched_places:
            if mp.lower() not in best.lower() and len(best) + len(mp) + 2 <= 42:
                return f"{best}, {mp}"
        return best

    if len(matched_places) >= 2:
        # Avoid redundant pairs like "USA, United States" or "Venice, Italy" ordering (city/region before country)
        p1, p2 = matched_places[0], matched_places[1]
        countries = {"Italy", "Switzerland", "France", "Germany", "Austria", "Spain", "Portugal", "United Kingdom", "United States", "Canada", "Japan", "China", "Australia"}
        if p1 in countries and p2 not in countries:
            return f"{p2}, {p1}"
        if p1 != p2 and p1 not in p2 and p2 not in p1:
            return f"{p1}, {p2}"
        return p1
    if len(matched_places) == 1:
        return matched_places[0]
    return ""


def extract_building(title, desc, categories_str, location):
    combined = f"{title} | {desc} | {categories_str}"
    for bld in ICONIC_BUILDINGS:
        if re.search(r"\b" + re.escape(bld) + r"\b", combined, flags=re.IGNORECASE):
            # Avoid duplicating if location already equals bld
            if location and bld.lower() == location.lower():
                return ""
            return bld

    # Named architectural structure pattern in title/categories
    m = re.search(
        r"\b([A-Z][A-Za-zÀ-ÿ'\-]+(?:\s+[A-Z][A-Za-zÀ-ÿ'\-]+){0,3}\s+(?:Bridge|Cathedral|Castle|Palace|Tower|Basilica|Opera House|Abbey|Mosque|Temple|Pavilion|Gate|Parliament|Capitol|Viaduct|Lighthouse))\b",
        f"{title} | {categories_str}",
    )
    if m:
        cand = m.group(1).strip()
        bad_prefixes = ("Featured ", "Quality ", "Old ", "New ", "View ", "Night ", "Interior ")
        if not cand.startswith(bad_prefixes) and 6 <= len(cand) <= 40:
            if not location or cand.lower() not in location.lower():
                return cand
    return ""


def is_true4k(w, h):
    if w < 3840 or h < 2160 or w <= h:
        return False
    ratio = float(w) / float(h)
    return 1.25 <= ratio <= 2.20


def mw_api_get(params):
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            if "429" in str(e):
                time.sleep(2.5 * (attempt + 1))
            else:
                time.sleep(0.8)
    return None


def fetch_esawebb_space_items(seen_urls, max_items=65):
    """Fetches official 3840x2160 UHD James Webb Space Telescope wallpapers from esawebb.org."""
    out = []
    for page in range(1, 5):
        feed_url = "https://esawebb.org/images/feed/" if page == 1 else f"https://esawebb.org/images/archive/search/?minimum_size=5&page={page}"
        try:
            req = urllib.request.Request(feed_url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=10) as r:
                body = r.read().decode("utf-8", errors="replace")
        except Exception:
            continue

        if page == 1:
            items_xml = re.findall(r"<item>(.*?)</item>", body, flags=re.DOTALL)
            for block in items_xml:
                m_id = re.search(r"https://esawebb\.org/images/([a-zA-Z0-9_-]+)/", block)
                m_title = re.search(r"<title>(.*?)</title>", block, flags=re.DOTALL)
                if not m_id or not m_title:
                    continue
                eid = m_id.group(1)
                if eid == "feed":
                    continue
                title = clean_html(m_title.group(1))
                # Clean trailing instrument code if messy, keep meaningful name
                title = re.sub(r"\s*\(Webb\s+Image\)\s*$", "", title, flags=re.IGNORECASE).strip()
                if not is_clean_human_title(title):
                    continue
                u4k = f"https://cdn.esawebb.org/archives/images/wallpaper5/{eid}.jpg"
                if u4k in seen_urls:
                    continue
                # Verify HEAD 200 & reasonable size (> 120 KB)
                try:
                    hr = urllib.request.Request(u4k, method="HEAD", headers={"User-Agent": UA})
                    with urllib.request.urlopen(hr, timeout=6) as hresp:
                        clen = int(hresp.headers.get("Content-Length") or 0)
                        if hresp.status != 200 or clen < 100_000:
                            continue
                except Exception:
                    continue
                seen_urls.add(u4k)
                out.append({
                    "title": title,
                    "artist": "NASA / ESA / CSA / STScI",
                    "location": "",
                    "building": "",
                    "url": u4k,
                    "w": 3840,
                    "h": 2160,
                })
                if len(out) >= max_items:
                    return out
    return out


def process_wikimedia_pages(pages, cat_key, seen_urls, seen_titles, items, target_count):
    for _, p in pages.items():
        if len(items) >= target_count:
            break
        ii_list = p.get("imageinfo") or []
        if not ii_list:
            continue
        ii = ii_list[0]
        orig_w = int(ii.get("width") or 0)
        orig_h = int(ii.get("height") or 0)
        orig_size = int(ii.get("size") or 0)
        orig_url = (ii.get("url") or "").split("?")[0]
        thumb_url = (ii.get("thumburl") or "").replace("https://thumb.wikimedia.org/", "https://upload.wikimedia.org/").split("?")[0]
        thumb_w = int(ii.get("thumbwidth") or orig_w)
        thumb_h = int(ii.get("thumbheight") or orig_h)

        if not is_true4k(orig_w, orig_h):
            continue

        # Prefer static Varnish-cached original JPEG when <= 9.5 MB & <= 6400px wide (zero dynamic thumb 429s!)
        chosen_url = ""
        chosen_w, chosen_h = orig_w, orig_h
        if (
            orig_url.lower().endswith((".jpg", ".jpeg"))
            and 100_000 <= orig_size <= 9_500_000
            and orig_w <= 6400
        ):
            chosen_url = orig_url
        elif thumb_url.lower().endswith((".jpg", ".jpeg")) and is_true4k(thumb_w, thumb_h):
            chosen_url = thumb_url
            chosen_w, chosen_h = thumb_w, thumb_h
        else:
            continue

        if chosen_url in seen_urls:
            continue

        ext = ii.get("extmetadata") or {}
        raw_file_title = (p.get("title") or "").replace("File:", "").strip()
        raw_obj_name = (ext.get("ObjectName") or {}).get("value", "")
        desc = clean_html((ext.get("ImageDescription") or {}).get("value", ""))
        cats_str = clean_html((ext.get("Categories") or {}).get("value", ""))
        artist = sanitize_artist((ext.get("Artist") or {}).get("value", ""))

        combined_lower = f"{raw_file_title} | {desc} | {cats_str}".lower()
        if any(bad in combined_lower for bad in SLOP_KEYWORDS):
            continue

        # Category-specific anti-slop quality gates
        if cat_key == "paintings":
            if any(cam in combined_lower for cam in PHOTO_CAMERA_MARKERS):
                continue
            if not any(
                kw in combined_lower
                for kw in ("painting", "oil on", "canvas", "watercolor", "tempera", "gouache", "fresco", "panel", "monet", "gogh", "renoir", "turner", "bierstadt", "aivazovsky", "canaletto", "rembrandt", "rubens", "bruegel", "cezanne", "cézanne", "pissarro", "sisley", "homer", "friedrich")
            ):
                continue

        if cat_key == "art":
            if any(
                bad_art in combined_lower
                for bad_art in (
                    "office building", "street view", "railway", "train ", "bridge in ", "aerial", "hotel ",
                    "cemetery", "grave", "war memorial", "memorial to", "park in", "square in", "horas de chumbo",
                )
            ):
                continue
            if not any(
                kw in combined_lower
                for kw in ("sculpture", "statue", "marble", "bronze", "stained glass", "stained-glass", "mosaic", "ukiyo-e", "hokusai", "hiroshige", "woodblock", "tapestry", "tapestries", "relief", "carving", "porcelain", "ceramic")
            ):
                continue

        if cat_key == "space":
            if any(bad_sp in combined_lower for bad_sp in ("portrait", "conference", "museum", "visitor", "diagram", "artist's impression of a satellite", "logo")):
                continue

        title, artist = clean_artwork_and_artist(raw_obj_name, raw_file_title, artist, cat_key)
        if not is_clean_human_title(title):
            # Try clean fallback from description first sentence for nature/urban
            if cat_key in ("nature", "urban") and desc and 8 <= len(desc) <= 72:
                title, artist = clean_artwork_and_artist(desc, desc, artist, cat_key)
            if not is_clean_human_title(title):
                continue

        norm_title = title.lower()
        if norm_title in seen_titles:
            continue

        location = ""
        building = ""
        if cat_key in ("nature", "urban"):
            location = extract_location(raw_file_title, desc, cats_str)
            if cat_key == "urban":
                building = extract_building(title, desc, cats_str, location)
            # Require authentic location (or building) on at least 90% of nature & urban photos
            if not location and not building:
                continue

        if not artist:
            if cat_key == "space":
                artist = "NASA / ESA / ESO"
            elif cat_key in ("paintings", "art"):
                for master in (
                    "Claude Monet", "Vincent van Gogh", "Pierre-Auguste Renoir", "Paul Cézanne",
                    "J. M. W. Turner", "Albert Bierstadt", "Ivan Aivazovsky", "Canaletto",
                    "Thomas Cole", "Caspar David Friedrich", "Camille Pissarro", "Alfred Sisley",
                    "Winslow Homer", "Frederic Edwin Church", "Rembrandt", "Peter Paul Rubens",
                    "Pieter Bruegel the Elder", "Katsushika Hokusai", "Utagawa Hiroshige",
                ):
                    if master.split()[-1].lower() in combined_lower:
                        artist = master
                        break
            if not artist and cat_key in ("nature", "urban", "macro"):
                continue

        seen_urls.add(chosen_url)
        seen_titles.add(norm_title)
        items.append({
            "title": title,
            "artist": artist,
            "location": location,
            "building": building,
            "url": chosen_url,
            "w": chosen_w,
            "h": chosen_h,
        })


def fetch_category_items(cat_key, global_seen_urls, target_count=TARGET_PER_CATEGORY):
    print(f"\n=== Curating '{cat_key}' 4K gallery (target {target_count}) ===", flush=True)
    raw_items = []
    seen_titles = set()

    # 1. Seed hand-verified iconic 4K leads first
    for lead in CURATED_LEADS.get(cat_key, []):
        if lead["url"] not in global_seen_urls:
            global_seen_urls.add(lead["url"])
            seen_titles.add(lead["title"].lower())
            raw_items.append(dict(lead))

    # 2. For space, pull official 3840x2160 James Webb UHD wallpapers from esawebb.org CDN
    if cat_key == "space":
        esa_items = fetch_esawebb_space_items(global_seen_urls, max_items=60)
        for ei in esa_items:
            if len(raw_items) < target_count and ei["title"].lower() not in seen_titles:
                seen_titles.add(ei["title"].lower())
                raw_items.append(ei)
        print(f"  + ESA/Webb official 4K UHD wallpapers added: {len(esa_items)}", flush=True)

    # 3. Curated Wikimedia Featured Picture & Master Artist category feeds
    for gcm_cat in CATEGORY_MEMBERS_FEEDS.get(cat_key, []):
        if len(raw_items) >= target_count:
            break
        gcm_cont = ""
        pages_fetched = 0
        while len(raw_items) < target_count and pages_fetched < 5:
            params = {
                "action": "query",
                "generator": "categorymembers",
                "gcmtitle": gcm_cat,
                "gcmtype": "file",
                "gcmlimit": "50",
                "prop": "imageinfo",
                "iiprop": "url|size|dimensions|extmetadata",
                "iiurlwidth": "3840",
                "format": "json",
            }
            if gcm_cont:
                params["gcmcontinue"] = gcm_cont
            data = mw_api_get(params)
            pages_fetched += 1
            if not data:
                break
            pages = (data.get("query") or {}).get("pages") or {}
            if not pages:
                break
            process_wikimedia_pages(pages, cat_key, global_seen_urls, seen_titles, raw_items, target_count)
            gcm_cont = (data.get("continue") or {}).get("gcmcontinue", "")
            if not gcm_cont:
                break
            time.sleep(0.08)
        print(f"  [Feed {gcm_cat}] -> {len(raw_items)}/{target_count}", flush=True)

    # 4. Curated search queries if still below target_count
    for q in SEARCH_QUERIES.get(cat_key, []):
        if len(raw_items) >= target_count:
            break
        offset = 0
        while len(raw_items) < target_count and offset < 2500:
            params = {
                "action": "query",
                "generator": "search",
                "gsrsearch": q,
                "gsrnamespace": "6",
                "gsrlimit": "50",
                "prop": "imageinfo",
                "iiprop": "url|size|dimensions|extmetadata",
                "iiurlwidth": "3840",
                "format": "json",
            }
            if offset > 0:
                params["gsroffset"] = str(offset)
            data = mw_api_get(params)
            if not data:
                break
            pages = (data.get("query") or {}).get("pages") or {}
            if not pages:
                break
            process_wikimedia_pages(pages, cat_key, global_seen_urls, seen_titles, raw_items, target_count)
            next_off = (data.get("continue") or {}).get("gsroffset")
            if next_off is None:
                break
            offset = int(next_off)
            time.sleep(0.08)
        print(f"  [Search] -> {len(raw_items)}/{target_count}", flush=True)

    # Assign sequential IDs (nat_001..nat_205, etc.)
    finalized = []
    for idx, it in enumerate(raw_items[:target_count], start=1):
        entry_id = f"{cat_key[:3]}_{idx:03d}"
        finalized.append({
            "id": entry_id,
            "cat": cat_key,
            "category": cat_key,
            "title": it["title"],
            "artist": it["artist"],
            "location": it["location"],
            "building": it["building"],
            "url": it["url"],
            "imageUrl": it["url"],
            "w": it["w"],
            "h": it["h"],
        })
    print(f"=== Completed '{cat_key}': {len(finalized)} curated 4K items ===", flush=True)
    return finalized


def download_starter_assets(all_items, starters_dir):
    """Downloads 1 verified 3840x2160 True-4K starter JPEG per category into app/assets/saver_starters/<cat>.jpg."""
    os.makedirs(starters_dir, exist_ok=True)
    print(f"\n--- Downloading 6 Instant True-4K Starter Assets to {starters_dir} ---", flush=True)
    for cat in CATEGORIES:
        cat_items = [x for x in all_items if x["category"] == cat]
        target_file = os.path.join(starters_dir, f"{cat}.jpg")
        saved = False
        # Pass 1: prefer curated lead True-4K JPEGs (<= 2.9 MB) so every category's #1 lead is an iconic masterpiece
        for max_bytes, pool in ((2_900_000, cat_items[:12]), (3_800_000, cat_items[:40])):
            if saved:
                break
            for cand in pool:
                u = cand["imageUrl"]
                try:
                    req = urllib.request.Request(u, headers={"User-Agent": UA})
                    with urllib.request.urlopen(req, timeout=25) as r:
                        data = r.read()
                    if "wikimedia.org" in u:
                        time.sleep(1.1)
                    if len(data) < 90_000 or len(data) > max_bytes:
                        continue
                    w, h = parse_jpeg_dimensions(data)
                    if not is_true4k(w, h):
                        continue
                    if cand["id"] != f"{cat[:3]}_001":
                        first = cat_items[0]
                        for k in ("title", "artist", "location", "building", "url", "imageUrl", "w", "h"):
                            first[k], cand[k] = cand[k], first[k]
                    with open(target_file, "wb") as f:
                        f.write(data)
                    print(f"  [OK] {cat}.jpg ({w}x{h}, {len(data)//1024} KB) -> '{cat_items[0]['title']}' by {cat_items[0]['artist']}", flush=True)
                    saved = True
                    break
                except Exception as e:
                    print(f"  [Retry {cat}] {e}", flush=True)
                    time.sleep(1.2)


def parse_jpeg_dimensions(data):
    """Parses actual pixel (width, height) directly from JPEG SOF markers with zero external dependencies."""
    if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8:
        return (0, 0)
    i = 2
    n = len(data)
    while i + 9 < n:
        if data[i] != 0xFF:
            i += 1
            continue
        while i < n and data[i] == 0xFF:
            i += 1
        if i >= n:
            break
        marker = data[i]
        i += 1
        if marker in (0xD8, 0xD9) or (0xD0 <= marker <= 0xD7) or marker == 0x01:
            continue
        if i + 2 > n:
            break
        seg_len = (data[i] << 8) | data[i + 1]
        if seg_len < 2 or i + seg_len > n:
            break
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            h = (data[i + 3] << 8) | data[i + 4]
            w = (data[i + 5] << 8) | data[i + 6]
            return (w, h)
        i += seg_len
    return (0, 0)


def validate_catalog(catalog_path):
    print(f"\n--- VALIDATING CATALOG MANIFEST: {catalog_path} ---", flush=True)
    if not os.path.exists(catalog_path):
        print(f"FAIL: Manifest not found at {catalog_path}")
        return False

    with open(catalog_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    items = data.get("items", [])
    if not items:
        print("FAIL: No items in catalog manifest")
        return False

    category_counts = {c: 0 for c in CATEGORIES}
    loc_counts = {c: 0 for c in CATEGORIES}
    bld_counts = {c: 0 for c in CATEGORIES}
    seen_ids = set()
    errors = 0

    for idx, it in enumerate(items):
        item_id = it.get("id")
        if not item_id or item_id in seen_ids:
            print(f"Error at #{idx}: duplicate or missing id '{item_id}'")
            errors += 1
        seen_ids.add(item_id)

        cat = (it.get("category") or it.get("cat") or "").lower()
        if cat not in CATEGORIES:
            print(f"Error at #{idx}: invalid category '{cat}'")
            errors += 1
        else:
            category_counts[cat] += 1
            if (it.get("location") or "").strip():
                loc_counts[cat] += 1
            if (it.get("building") or "").strip():
                bld_counts[cat] += 1

        w = it.get("w", 0)
        h = it.get("h", 0)
        if not is_true4k(w, h):
            print(f"Error at #{idx}: non-4K dimensions ({w}x{h}) for item {item_id}")
            errors += 1

        url = it.get("url") or it.get("imageUrl")
        if not url or not url.startswith("https://"):
            print(f"Error at #{idx}: invalid URL '{url}' for item {item_id}")
            errors += 1

        title = it.get("title")
        if not title or len(title) < 4:
            print(f"Error at #{idx}: missing or too short title for item {item_id}")
            errors += 1

    print(f"Total entries evaluated: {len(items)}")
    for cat in CATEGORIES:
        cnt = category_counts[cat]
        status = "PASS" if cnt >= 200 else "FAIL"
        print(f"  Category '{cat:10s}': {cnt} entries [{status}] | with_location={loc_counts[cat]} | with_building={bld_counts[cat]}")
        if cnt < 200:
            errors += 1

    if errors == 0:
        print(f"=== ALL CHECKS PASSED: {len(items)} verified True-4K entries ===\n")
        return True
    print(f"=== VALIDATION FAILED with {errors} errors ===\n")
    return False


def main():
    base_dir = os.path.join(os.path.dirname(__file__), "..", "app", "assets")
    catalog_path = os.path.join(base_dir, "saver_catalog.json")
    starters_dir = os.path.join(base_dir, "saver_starters")

    if "--validate" in sys.argv:
        sys.exit(0 if validate_catalog(catalog_path) else 1)

    global_seen_urls = set()
    all_items = []
    for cat in CATEGORIES:
        cat_items = fetch_category_items(cat, global_seen_urls, TARGET_PER_CATEGORY)
        all_items.extend(cat_items)

    download_starter_assets(all_items, starters_dir)

    os.makedirs(base_dir, exist_ok=True)
    manifest = {
        "version": 3,
        "total": len(all_items),
        "items": all_items,
    }
    with open(catalog_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    print(f"Wrote curated manifest ({len(all_items)} items) to {catalog_path}", flush=True)
    sys.exit(0 if validate_catalog(catalog_path) else 1)


if __name__ == "__main__":
    main()
