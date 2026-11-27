#!/usr/bin/env python3
"""Sends private secret keys to MyTV on the TV via Android's ADB-only Secrets ContentProvider, so passwords,
Personal Access Tokens, and Smart-Home credentials NEVER sit inside classes.dex or unencrypted XML.

    python3 tools/send_key.py 192.168.1.50 sensibo_api_key   # prompts for a single key (never echoed)
    python3 tools/send_key.py 192.168.1.50 --sync-config     # pushes all configured keys from config.properties / keys.txt
    python3 tools/send_key.py --remove github_token           # removes a key from the TV

Keys are encrypted at rest on the TV using AndroidKeyStore AES-256-GCM (Secrets.java). Raw secret values are NEVER
printed or logged to stdout/stderr.
"""
import getpass
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

URI = 'content://io.mytv.secrets/'
NAMES = (
    'adb_key',
    'sensibo_api_key',
    'tuya_access_id',
    'tuya_access_secret',
    'github_token',
    'stremio_auth_key',
    'household_key',
    'gaming_pc_token',
    'tmdb_key',
    'omdb_key',
    'mdblist_key',
    'gemini_api_key',
    'admin_emails',
    'remote_paired_devices',
    'wiz_light_ips',
    'switcher_api_key',
    'enable_gaming',
    'enable_smarthome',
)
SYNC_CONFIG_KEYS = (
    'sensibo_api_key',
    'tuya_access_id',
    'tuya_access_secret',
    'github_token',
    'stremio_auth_key',
    'household_key',
    'gaming_pc_token',
    'tmdb_key',
    'omdb_key',
    'mdblist_key',
    'gemini_api_key',
    'admin_emails',
    'wiz_light_ips',
    'enable_gaming',
    'enable_smarthome',
)
SERIAL = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(ROOT)


def adb(*args, **kw):
    # Not this process's stdin: `adb shell` would read the key piped in for `content write` (and send it to the TV).
    if 'input' not in kw:
        kw['stdin'] = subprocess.DEVNULL
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, **kw)


def _read_kv_file(path):
    values = {}
    try:
        with open(path, encoding='utf-8') as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or line.startswith('#'):
                    continue
                k, sep, v = line.partition('=')
                if sep:
                    clean_k = k.strip().lower()
                    clean_v = v.strip().strip('"').strip("'")
                    if clean_k and clean_v:
                        values[clean_k] = clean_v
    except OSError:
        pass
    return values


def load_local_config_map():
    """Loads local key-value pairs from ../keys.txt and config.properties (config.properties overrides keys.txt).
    Never prints or logs any secret values."""
    merged = {}
    for candidate in (os.path.join(REPO_ROOT, 'keys.txt'), os.path.join(ROOT, 'config.properties')):
        merged.update(_read_kv_file(candidate))
    return merged


def prop(key):
    """A value from config.properties or ../keys.txt, or ''."""
    return load_local_config_map().get(key.lower(), '')


def received(name):
    """When the TV got this key (Secrets' `received`, ms), 0 if it has none, None if it didn't answer."""
    r = adb('shell', 'content', 'query', '--uri', URI + name, text=True)
    m = re.search(r'present=(\d+), received=(\d+)', r.stdout)
    if not m:
        return None
    return int(m.group(2)) if m.group(1) == '1' else 0


def settled(name, done):
    """received(name) once done(it) holds, or its last answer: the app encrypts the key into place when write closes."""
    r = None
    for _ in range(10):
        r = received(name)
        if done(r):
            return r
        time.sleep(0.5)
    return r


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """The key isn't sent on to wherever a redirect points, as on the TV."""
    def redirect_request(self, *args):
        return None


CHECKS = {}


def exchange_stremio_login(email, password):
    """Exchanges Stremio email + password once locally against https://api.strem.io/api/login and returns ONLY authKey."""
    if not email or not password:
        return ''
    payload = json.dumps({
        'type': 'Login',
        'email': email.strip(),
        'password': password,
        'facebook': False,
    }).encode('utf-8')
    req = urllib.request.Request(
        'https://api.strem.io/api/login',
        data=payload,
        headers={'Content-Type': 'application/json; charset=UTF-8', 'Accept': 'application/json'},
        method='POST',
    )
    try:
        opener = urllib.request.build_opener(NoRedirect)
        with opener.open(req, timeout=10) as resp:
            data = json.load(resp)
            result = data.get('result') or {}
            return str(result.get('authKey') or '').strip()
    except Exception:
        return ''


def verify_tv_ready():
    listed = adb('shell', 'pm', 'list', 'packages', 'io.mytv', text=True)
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return False
    if 'package:io.mytv' not in listed.stdout.split():
        print('MyTV (io.mytv) is not installed on this TV.')
        return False
    sdk = adb('shell', 'getprop', 'ro.build.version.sdk', text=True).stdout.strip()
    if sdk.isdigit() and int(sdk) < 28:
        print('This TV runs Android 8, where adb has no way to write into the app: keys need Android 9 or newer.')
        return False
    return True


def push_secret(name, secret_value):
    before = received(name)
    if before is None:
        print(f'The MyTV build on this TV does not accept {name} yet: install the updated MyTV.apk first.', flush=True)
        return False
    payload = secret_value.encode('utf-8') + b'\n'
    after = before
    for attempt in range(4):
        if attempt > 0:
            if len(SERIAL) == 2:
                adb('connect', SERIAL[1], text=True)
            time.sleep(0.4)
        adb('exec-in', 'content', 'write', '--uri', URI + name, input=payload)
        after = settled(name, lambda r: bool(r) and r != before)
        if after and after != before:
            break
    if not after or after == before:
        print(f'The TV rejected {name} (must be 4..16384 printable ASCII characters).', flush=True)
        return False
    print(f'Synced {name} (encrypted at rest via AndroidKeyStore).', flush=True)
    return True

def sync_from_config():
    if not verify_tv_ready():
        return 1
    cfg = load_local_config_map()
    if not cfg.get('stremio_auth_key') and cfg.get('stremio_email') and cfg.get('stremio_password'):
        print('Exchanging stremio_email + stremio_password for session stremio_auth_key...')
        exchanged = exchange_stremio_login(cfg.get('stremio_email', ''), cfg.get('stremio_password', ''))
        if exchanged:
            cfg['stremio_auth_key'] = exchanged
        else:
            print('Warning: Stremio login exchange did not return an authKey; skipping stremio_auth_key.')

    pushed = 0
    failed = 0
    for key_name in SYNC_CONFIG_KEYS:
        val = cfg.get(key_name, '').strip()
        if not val or len(val) < 4:
            continue
        if push_secret(key_name, val):
            pushed += 1
        else:
            failed += 1

    print(f'Config sync complete: {pushed} key(s) pushed, {failed} failed.')
    return 0 if failed == 0 else 1


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0 if args else 2
    remove = '--remove' in args
    sync_cfg = '--sync-config' in args
    check = '--no-check' not in args
    args = [a for a in args if a not in ('--remove', '--no-check', '--sync-config')]
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    if args and args[0][0].isdigit():
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target, text=True)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
        SERIAL[:] = ['-s', target]

    if sync_cfg:
        return sync_from_config()

    if len(args) != 1 or args[0] not in NAMES:
        print('Which key? One of: ' + ', '.join(NAMES) + ' (or pass --sync-config)')
        return 2
    name = args[0]
    if not verify_tv_ready():
        return 1
    before = received(name)
    if before is None:
        print('The MyTV on this TV is too old to take this key: install the updated MyTV.apk, then send it again.')
        return 1

    if remove:
        adb('shell', 'content', 'delete', '--uri', URI + name)
        if settled(name, lambda r: r == 0) != 0:
            print(f'{name} is still on the TV: try again.')
            return 1
        print(f'{name} is off the TV.')
        return 0
    key = (getpass.getpass(f'{name} (not shown): ') if sys.stdin.isatty() else sys.stdin.readline()).strip()
    if not key:
        print('No key given.')
        return 1
    if check and name in CHECKS:
        why = CHECKS[name](key)
        if why:
            print(why)
            return 1
    return 0 if push_secret(name, key) else 1


if __name__ == '__main__':
    sys.exit(main())
