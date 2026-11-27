#!/usr/bin/env python3
"""
Empirical Validator & Stress Checker for saver_catalog.json (Milestone 2 Challenger).
"""
import json
import os
import sys

def validate_catalog(json_path):
    print(f"=== VALIDATING 4K SCREENSAVER CATALOG: {json_path} ===")
    if not os.path.isfile(json_path):
        print(f"ERROR: Catalog file not found: {json_path}")
        return False

    with open(json_path, 'r', encoding='utf-8') as f:
        try:
            data = json.load(f)
        except Exception as e:
            print(f"ERROR: JSON decoding failed: {e}")
            return False

    if isinstance(data, dict):
        items = data.get("items", [])
    elif isinstance(data, list):
        items = data
    else:
        print(f"ERROR: Unexpected root JSON type: {type(data)}")
        return False

    print(f"Total entries loaded: {len(items)}")
    expected_categories = {"nature", "urban", "macro", "space", "paintings", "art"}
    category_counts = {c: 0 for c in expected_categories}
    category_items = {c: [] for c in expected_categories}
    seen_ids = set()
    errors = []

    for idx, item in enumerate(items):
        item_id = item.get("id")
        if not item_id:
            errors.append(f"Entry {idx}: Missing 'id'")
            continue
        if item_id in seen_ids:
            errors.append(f"Entry {idx}: Duplicate id '{item_id}'")
        seen_ids.add(item_id)

        cat = (item.get("category") or item.get("cat") or "").strip().lower()
        if not cat:
            errors.append(f"Entry '{item_id}': Missing category")
            continue
        if cat not in expected_categories:
            errors.append(f"Entry '{item_id}': Unknown category '{cat}'")
            continue
        category_counts[cat] += 1
        category_items[cat].append(item)

        # Resolution checks
        w = item.get("w")
        h = item.get("h")
        if not isinstance(w, int) or not isinstance(h, int):
            errors.append(f"Entry '{item_id}': Dimensions w={w}, h={h} are not integers")
            continue

        if w < 3840:
            errors.append(f"Entry '{item_id}': Width {w} is below 3840 (MIN_4K_WIDTH)")
        if h < 2160:
            errors.append(f"Entry '{item_id}': Height {h} is below 2160 (MIN_4K_HEIGHT)")
        if w <= h:
            errors.append(f"Entry '{item_id}': Non-landscape image w={w} <= h={h}")

        ratio = w / float(h)
        if ratio < 1.25 or ratio > 2.20:
            errors.append(f"Entry '{item_id}': Aspect ratio {ratio:.3f} outside [1.25, 2.20]")

        # URL checks
        url = item.get("imageUrl") or item.get("url")
        if not url:
            errors.append(f"Entry '{item_id}': Missing image URL")
        elif not url.startswith("https://"):
            errors.append(f"Entry '{item_id}': URL is not secure HTTPS: {url}")

        # Metadata hygiene
        title = item.get("title", "")
        if not title or not title.strip():
            errors.append(f"Entry '{item_id}': Missing title")
        elif "<" in title or ">" in title:
            errors.append(f"Entry '{item_id}': Title contains raw HTML: {title}")

    # Check category distribution
    for c in sorted(expected_categories):
        count = category_counts[c]
        status = "PASS" if count >= 200 else "FAIL"
        print(f"  Category '{c}': {count} entries (minimum 200) [{status}]")
        if count < 200:
            errors.append(f"Category '{c}' has only {count} entries (mandate: >= 200)")

    if errors:
        print(f"\nFAILED with {len(errors)} error(s):")
        for e in errors[:25]:
            print(f"  - {e}")
        if len(errors) > 25:
            print(f"  ... and {len(errors) - 25} more errors.")
        return False

    print(f"\n=== SUCCESS: All {len(items)} items satisfy 4K UHD bounds, aspect ratios, HTTPS, and category counts ===")
    return True

if __name__ == "__main__":
    catalog_path = sys.argv[1] if len(sys.argv) > 1 else "SoarTV-launcher/app/assets/saver_catalog.json"
    success = validate_catalog(catalog_path)
    sys.exit(0 if success else 1)
