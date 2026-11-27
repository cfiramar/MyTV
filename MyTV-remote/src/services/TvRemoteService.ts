/**
 * Strict TypeScript domain interfaces and service class for the MyTV Companion Remote App.
 * Encapsulates all HTTP/REST communication, hardware-backed Bearer authentication, QR/6-digit
 * pairing, Google Admin OAuth recovery, Role-Based Access Control (RBAC), and wireless PC unlock
 * with the TV Launcher's embedded RemoteControlServer (:8484).
 */

export type RemoteRole = 'GUEST' | 'HOUSEHOLD' | 'ADMIN';

export interface UserProfile {
  id: string;
  username: string;
  displayName: string;
  avatarColor: string;
  traktUsername: string;
  isDefault: boolean;
}

export interface SmartHomeDevice {
  id: string;
  name: string;
  protocol: 'SENSIBO_AC' | 'WIZ_LIGHT' | 'SWITCHER_BOILER' | 'TUYA_AC' | string;
  icon?: string;
  symbol?: string;
  isOn: boolean;
  on?: boolean;
  statusText: string;
  status?: string;
  targetTempCelsius: number;
  targetTemp?: number;
  acMode?: 'cool' | 'heat' | 'dry' | 'fan' | string;
  fanLevel?: 'auto' | 'low' | 'medium' | 'high' | string;
  brightness?: number;
  colorTempK?: number;
  timerMinutes?: number;
  macAddress?: string;
  currentIp?: string;
}

export interface SharedReel {
  id: string;
  sender: string;
  url: string;
  platform: string;
  title: string;
}

export interface ShelfAppItem {
  pkg: string;
  label: string;
}

export interface GamingGameItem {
  id: string;
  title: string;
  category: 'system' | 'steam' | 'standalone' | string;
  cmd: string;
  hidden: boolean;
  imageUrl?: string;
  lastPlayedAt?: number;
}

export interface GamingControllerTelemetry {
  descriptor: string;
  name: string;
  deviceName?: string;
  customName?: string;
  color: string;
  colorName?: string;
  tagged: boolean;
  connected: boolean;
  batteryPercent: number;
  charging: boolean;
  powerStatus?: string;
}

export interface GamingStatus {
  laptopIp: string;
  awake: boolean;
  locked: boolean;
  hdmiConnected: boolean;
  sunshineRunning: boolean;
  batteryPercent?: number;
  batteryCharging?: boolean;
  batteryFull?: boolean;
  controllerSummary?: string;
  connectedControllers?: string[];
  controllers?: GamingControllerTelemetry[];
  allKnownControllers?: GamingControllerTelemetry[];
  continuePlaying?: GamingGameItem[];
  games: GamingGameItem[];
}

/**
 * Formats a connected controller's pill label with battery percentage or connected-to-power state.
 */
export function formatControllerBadgeLabel(
  ctrl: Pick<GamingControllerTelemetry, 'name' | 'batteryPercent' | 'charging'>
): string {
  const base = (ctrl.name || '').trim() || 'Gamepad';
  if (typeof ctrl.batteryPercent === 'number' && ctrl.batteryPercent >= 0) {
    return `${base} · ${ctrl.batteryPercent}%`;
  }
  if (ctrl.charging) {
    return `${base} · Connected to Power`;
  }
  return base;
}

export type RemoteDockTabId = 'remote' | 'gaming' | 'smarthome' | 'cast' | 'apps';
export type SmartHomeIconKind = 'ac' | 'lightbulb' | 'shower' | 'device';

export interface ResolvedTabVisibility {
  gaming: boolean;
  reels: boolean;
  smarthome: boolean;
  visibleDockTabs: RemoteDockTabId[];
  visibleScreenIndices: number[];
}

export const MYTV_THEME_META = {
  dark: {
    themeColor: '#0E1024',
    navColor: '#0A1522',
  },
  light: {
    themeColor: '#F7F9FF',
    navColor: '#EEF3FC',
  },
} as const;

/**
 * Resolves a quick tap at normalized coordinates `(relX, relY)` in `[0..1]` on the Liquid Glass
 * Clickpad into directional edge clicks (`up`/`down`/`left`/`right`) or center `ok`.
 */
export function resolveTouchpadTapAction(
  relX: number,
  relY: number
): 'up' | 'down' | 'left' | 'right' | 'ok' {
  const dx = relX - 0.5;
  const dy = relY - 0.5;
  const edgeThreshold = 0.28;
  if (Math.abs(dx) < edgeThreshold && Math.abs(dy) < edgeThreshold) {
    return 'ok';
  }
  if (Math.abs(dx) > Math.abs(dy)) {
    return dx < 0 ? 'left' : 'right';
  }
  return dy < 0 ? 'up' : 'down';
}

/**
 * Returns the Twilight Aurora (Dark) or Luminous Pearl Glass (Light) system bar colors.
 */
export function getThemeColorMeta(dark: boolean): { themeColor: string; navColor: string } {
  return dark ? MYTV_THEME_META.dark : MYTV_THEME_META.light;
}

/**
 * Resolves visible TV Top-Bar screen indices and mobile Bottom Dock tabs from `TvStatus.enabledTabs`
 * so hidden optional TV tabs (Gaming, Smart-Home, Reels) never appear on the phone remote.
 */
export function resolveTabVisibility(enabledTabs?: TvStatus['enabledTabs']): ResolvedTabVisibility {
  const gaming = enabledTabs ? enabledTabs.gaming !== false : true;
  const reels = enabledTabs ? enabledTabs.reels === true : false;
  const smarthome = enabledTabs ? enabledTabs.smarthome !== false : true;

  const visibleDockTabs: RemoteDockTabId[] = ['remote'];
  if (gaming) visibleDockTabs.push('gaming');
  if (smarthome) visibleDockTabs.push('smarthome');
  if (reels) visibleDockTabs.push('cast');
  visibleDockTabs.push('apps');

  const visibleScreenIndices: number[] = [0, 1, 2];
  if (gaming) visibleScreenIndices.push(3);
  if (reels) visibleScreenIndices.push(4);
  if (smarthome) visibleScreenIndices.push(5);
  visibleScreenIndices.push(6);

  return {
    gaming,
    reels,
    smarthome,
    visibleDockTabs,
    visibleScreenIndices,
  };
}

/**
 * Resolves the fallback mobile dock tab when the currently active tab becomes disabled by `enabledTabs`.
 */
export function resolveFallbackDockTab(
  activeTabId: string,
  visibility: ResolvedTabVisibility
): RemoteDockTabId {
  if (visibility.visibleDockTabs.includes(activeTabId as RemoteDockTabId)) {
    return activeTabId as RemoteDockTabId;
  }
  return 'remote';
}

/**
 * Maps a Smart-Home device protocol/symbol to its monochrome vector icon kind matching `smarthome.webp`.
 */
export function resolveSmartHomeIconKind(
  device: Pick<SmartHomeDevice, 'protocol' | 'icon' | 'symbol'>
): SmartHomeIconKind {
  const proto = (device.protocol || '').toUpperCase();
  const sym = (device.symbol || device.icon || '').toLowerCase();
  if (proto === 'SENSIBO_AC' || proto === 'TUYA_AC' || sym === 'ac' || sym === 'snowflake') {
    return 'ac';
  }
  if (proto === 'WIZ_LIGHT' || sym === 'lightbulb' || sym === 'lamp') {
    return 'lightbulb';
  }
  if (proto === 'SWITCHER_BOILER' || sym === 'shower' || sym === 'flame' || sym === 'boiler') {
    return 'shower';
  }
  return 'device';
}

/**
 * Formats a game card subtitle below 16:9 artwork matching `GamingScreenRenderer.formatGameSubtitle`.
 */
export function formatGameSubtitle(
  game: Pick<GamingGameItem, 'category' | 'hidden' | 'lastPlayedAt'>
): string {
  const cat = (game.category || '').toLowerCase();
  let base = 'PC Game';
  if (cat === 'steam') base = 'Steam';
  else if (cat === 'heroic') base = 'Heroic Games';
  else if (cat === 'epic' || cat === 'legendary') base = 'Epic Games';
  if (game.hidden) return `${base} · Hidden`;
  if (typeof game.lastPlayedAt === 'number' && game.lastPlayedAt > 0) {
    return `${base} · Recently Played`;
  }
  return base;
}

export interface TvStatus {
  tvModel: string;
  tvBrand: string;
  remoteUrl: string;
  geminiConfigured?: boolean;
  authRole?: RemoteRole;
  guestExpiresAtMs?: number;
  deviceId?: string;
  activeScreen?: number;
  subTab?: number;
  sideShelfOpen?: boolean;
  darkMode?: boolean;
  searchQuery?: string;
  traktConnected?: boolean;
  traktUser?: string;
  controllerSummary?: string;
  enabledTabs?: {
    gaming?: boolean;
    reels?: boolean;
    smarthome?: boolean;
  };
  shelfApps?: ShelfAppItem[];
  activeUser: UserProfile;
  users: UserProfile[];
  smartHome: SmartHomeDevice[];
  sharedReels: SharedReel[];
}

export interface AuthConfigResponse {
  paired: boolean;
  role: RemoteRole | '';
  guestExpired: boolean;
  expiresAtMs: number;
  googleAuthEnabled: boolean;
  supabaseUrl: string;
  tvName: string;
  bootstrapAdmin: boolean;
  handoffToken?: string;
  handoffRole?: RemoteRole | '';
}

export interface ParsedQrPairingPayload {
  baseUrl: string;
  qrSecret: string;
  code: string;
  token: string;
  role: RemoteRole | '';
}

export interface PairRequestResponse {
  ok: boolean;
  requestId: string;
  expiresAtMs: number;
  bootstrapAdmin: boolean;
}

export interface PairPollResponse {
  status: 'pending' | 'approved' | 'dismissed' | 'expired';
  ok?: boolean;
  token?: string;
  deviceId?: string;
  role?: RemoteRole;
  expiresAtMs?: number;
}

export interface PairVerifyResponse {
  ok: boolean;
  token: string;
  deviceId: string;
  role: RemoteRole;
  expiresAtMs: number;
}

export interface PairedDeviceInfo {
  deviceId: string;
  deviceName: string;
  role: RemoteRole;
  googleEmail: string;
  createdAtMs: number;
  expiresAtMs: number;
  lastSeenMs: number;
  expired: boolean;
  daysRemaining: number;
  isCurrentDevice: boolean;
}

export interface GoogleAuthResponse {
  ok: boolean;
  role: 'ADMIN';
  token: string;
  deviceId: string;
  email: string;
  devices: PairedDeviceInfo[];
}

export interface AdminInviteResponse {
  ok: boolean;
  requestId: string;
  code: string;
  qrSecret: string;
  pairUrl: string;
  role: RemoteRole;
  expiresAtMs: number;
}

export interface AiQueryResult {
  summary?: string;
  titles?: string[];
  topItem?: {
    name: string;
    deepLink?: string;
  };
  error?: string;
}

/**
 * Thrown when a protected Companion Remote endpoint returns HTTP 401 (unpaired, revoked, or 30-day Guest expired).
 */
export class RemoteUnauthorizedError extends Error {
  public readonly status = 401;
  public readonly errorCode: string;
  public readonly guestExpired: boolean;
  public readonly canRepair: boolean;

  constructor(errorCode: string = 'unauthorized', canRepair: boolean = true) {
    super(
      errorCode === 'guest_expired'
        ? 'Guest remote access expired after 30 days'
        : 'Companion Remote pairing required'
    );
    this.name = 'RemoteUnauthorizedError';
    this.errorCode = errorCode;
    this.guestExpired = errorCode === 'guest_expired';
    this.canRepair = canRepair;
  }
}

/**
 * Thrown when an authenticated Companion Remote device lacks role permission for an endpoint (HTTP 403).
 */
export class RemoteForbiddenError extends Error {
  public readonly status = 403;
  public readonly errorCode: string;
  public readonly requiredRole: string;

  constructor(errorCode: string = 'insufficient_role', requiredRole: string = '') {
    super(`Insufficient remote role (${errorCode})`);
    this.name = 'RemoteForbiddenError';
    this.errorCode = errorCode;
    this.requiredRole = requiredRole;
  }
}

export type SmartHomeRoutineId =
  | 'movie_time'
  | 'good_night'
  | 'all_lights_bright'
  | 'discover_wiz'
  | 'google_home_voice';

export type RemoteKeyName =
  | 'up'
  | 'down'
  | 'left'
  | 'right'
  | 'ok'
  | 'hold_ok'
  | 'menu'
  | 'back'
  | 'home'
  | 'apps'
  | 'mute'
  | 'vol_up'
  | 'vol_down'
  | 'gaming'
  | 'reels'
  | 'smarthome'
  | 'settings'
  | 'sideshelf'
  | 'subtab_tv'
  | 'subtab_movie'
  | 'theme'
  | 'search'
  | 'users'
  | 'trakt';

export interface FetchInitLike {
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  signal?: AbortSignal;
}

export interface FetchResponseLike {
  ok: boolean;
  status: number;
  json: () => Promise<unknown>;
}

export type FetchLike = (input: string, init?: FetchInitLike) => Promise<FetchResponseLike>;

export interface TvRemoteServiceOptions {
  authToken?: string;
  timeoutMs?: number;
  enforceTimeoutOnCustomFetch?: boolean;
  onAuthError?: ((err: RemoteUnauthorizedError | RemoteForbiddenError) => void) | null;
}

/**
 * Service encapsulating all remote control, Smart Home, Multi-User, Pairing, Google Admin OAuth,
 * Device Management, Wireless PC Unlock, and media broadcast operations against the embedded MyTV server.
 */
export class TvRemoteService {
  public static readonly DEFAULT_TIMEOUT_MS = 3500;
  public static readonly NATIVE_REMOTE_HOST = 'remote.mytv.local';
  public static readonly NATIVE_AUTH_REDIRECT_URI = 'mytvremote://auth';

  private baseUrl: string;
  private authToken: string;
  private readonly fetchFn: FetchLike;
  private onAuthError: ((err: RemoteUnauthorizedError | RemoteForbiddenError) => void) | null;

  /**
   * Creates a new TvRemoteService instance.
   *
   * @param baseUrl Base URL of the TV's embedded server (e.g. "http://192.168.1.50:8484").
   * @param fetchFn Injectable fetch implementation for testability (Dependency Inversion).
   * @param options Optional initial auth token, timeout configuration, and auth error listener.
   */
  constructor(baseUrl: string = '', fetchFn?: FetchLike, options?: TvRemoteServiceOptions) {
    this.baseUrl = TvRemoteService.normalizeBaseUrl(baseUrl);
    this.authToken = (options?.authToken || '').trim();
    this.onAuthError = options?.onAuthError ?? null;
    const timeoutMs = options?.timeoutMs ?? TvRemoteService.DEFAULT_TIMEOUT_MS;
    if (fetchFn) {
      this.fetchFn = options?.enforceTimeoutOnCustomFetch
        ? TvRemoteService.createTimeoutFetch(fetchFn, timeoutMs)
        : fetchFn;
    } else {
      this.fetchFn = TvRemoteService.createTimeoutFetch(
        (url, init) => fetch(url, init as RequestInit),
        timeoutMs
      );
    }
  }

  /**
   * Wraps a fetch implementation with an AbortController hard timeout (defaults to 3500ms, BP-09).
   */
  public static createTimeoutFetch(
    rawFetch: FetchLike,
    timeoutMs: number = TvRemoteService.DEFAULT_TIMEOUT_MS
  ): FetchLike {
    return async (input: string, init?: FetchInitLike): Promise<FetchResponseLike> => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const nextInit: FetchInitLike = init
          ? { ...init, signal: controller.signal }
          : { signal: controller.signal };
        return await rawFetch(input, nextInit);
      } finally {
        clearTimeout(timer);
      }
    };
  }

  /**
   * Normalizes a TV server base URL by stripping trailing slashes.
   */
  public static normalizeBaseUrl(url: string): string {
    return url.trim().replace(/\/+$/, '');
  }

  /**
   * Normalizes a user-supplied IP or URL into `http://<host>:<port>` (or empty string if blank).
   */
  public static normalizeHostInput(raw: string, defaultPort: number = 8484): string {
    const cleaned = (raw || '').trim().replace(/\/+$/, '');
    if (!cleaned) return '';
    if (/^https?:\/\//i.test(cleaned)) {
      return cleaned;
    }
    const hasPort = /:\d+$/.test(cleaned);
    return `http://${cleaned}${hasPort ? '' : `:${defaultPort}`}`;
  }

  /**
   * Resolves the active TV base URL dynamically without any hardcoded LAN IP (INC-02).
   * When served directly by the TV on port 8484, always prefers the live `loc.origin` so router/Wi-Fi
   * IP changes work automatically; inside `MyTV-Remote.apk` (`remote.mytv.local`), uses `savedBaseUrl`.
   */
  public static resolveDynamicBaseUrl(
    loc: { protocol: string; hostname: string; port: string; origin: string },
    savedBaseUrl: string
  ): string {
    const host = (loc.hostname || '').trim().toLowerCase();
    const saved = TvRemoteService.normalizeHostInput(savedBaseUrl);
    const isSyntheticNative =
      loc.protocol === 'file:' || host === TvRemoteService.NATIVE_REMOTE_HOST || !host;

    if (isSyntheticNative) {
      return saved;
    }
    if (loc.port === '8484' && (loc.protocol === 'http:' || loc.protocol === 'https:')) {
      return TvRemoteService.normalizeBaseUrl(loc.origin);
    }
    if (saved) {
      return saved;
    }
    if (loc.protocol === 'http:' || loc.protocol === 'https:') {
      return TvRemoteService.normalizeBaseUrl(loc.origin);
    }
    return '';
  }

  /**
   * Probes candidate LAN TV addresses in parallel via `/api/auth/config` and returns the first responding base URL.
   */
  public static async probeLanTv(
    candidates: string[],
    fetchImpl?: FetchLike,
    probeTimeoutMs: number = 1200
  ): Promise<string | null> {
    const normalized = Array.from(
      new Set(candidates.map((c) => TvRemoteService.normalizeHostInput(c)).filter(Boolean))
    );
    if (normalized.length === 0) return null;

    const baseFetch: FetchLike = fetchImpl ?? ((url, init) => fetch(url, init as RequestInit));
    const timedFetch = TvRemoteService.createTimeoutFetch(baseFetch, probeTimeoutMs);

    const checks = normalized.map(async (base): Promise<string> => {
      const res = await timedFetch(`${base}/api/auth/config`, { method: 'GET' });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const data = (await res.json()) as Partial<AuthConfigResponse>;
      if (typeof data.paired !== 'boolean') {
        throw new Error('Not a MyTV server');
      }
      return base;
    });

    try {
      return await Promise.any(checks);
    } catch {
      return null;
    }
  }

  /**
   * Builds prioritized candidate URLs (`http://<ip>:8484`) across mDNS and the `/24` IPv4 subnet
   * of `hintUrlOrIp` (or common home LAN subnets when no hint exists) so router DHCP IP changes
   * (such as `.27` -> `.8` after a power outage) recover automatically without manual entry.
   */
  public static buildSubnetCandidates(hintUrlOrIp: string = '', port: number = 8484): string[] {
    const normalizedHint = TvRemoteService.normalizeHostInput(hintUrlOrIp, port);
    const out: string[] = [
      `http://Android.local:${port}`,
      `http://mytv.local:${port}`,
    ];
    const seen = new Set<string>(out);

    const ipv4Match = normalizedHint.match(/^https?:\/\/(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::\d+)?$/i);
    const prefixes: string[] = [];
    let skipExact = '';
    if (ipv4Match) {
      prefixes.push(`${ipv4Match[1]}.${ipv4Match[2]}.${ipv4Match[3]}`);
      skipExact = normalizedHint;
    }
    for (const fallbackPrefix of ['192.168.10', '192.168.1', '192.168.0', '10.0.0']) {
      if (!prefixes.includes(fallbackPrefix) && prefixes.length < 2) {
        prefixes.push(fallbackPrefix);
      }
    }

    for (const prefix of prefixes) {
      // Check common low router DHCP leases (.2 .. .64) first, then remaining (.65 .. .254)
      for (let host = 2; host <= 254; host++) {
        const candidate = `http://${prefix}.${host}:${port}`;
        if (candidate === skipExact) continue;
        if (!seen.has(candidate)) {
          seen.add(candidate);
          out.push(candidate);
        }
      }
    }
    return out;
  }

  /**
   * Automatically discovers a MyTV Launcher server on the local Wi-Fi network in fast parallel
   * batches when the previously cached IP is unreachable (e.g. after router or TV power-cycle).
   */
  public static async discoverLanTv(
    lastKnownBaseUrl: string = '',
    fetchImpl?: FetchLike,
    batchSize: number = 6,
    probeTimeoutMs: number = 650
  ): Promise<string | null> {
    if (!fetchImpl && typeof window !== 'undefined') {
      const w = window as unknown as {
        MyTvNative?: { discoverTv?: (hint: string) => string };
      };
      if (w.MyTvNative && typeof w.MyTvNative.discoverTv === 'function') {
        try {
          const nativeFound = TvRemoteService.normalizeHostInput(
            w.MyTvNative.discoverTv(lastKnownBaseUrl || '')
          );
          if (nativeFound) {
            return nativeFound;
          }
        } catch {
          // Fall through to JS subnet scan
        }
      }
    }
    const candidates = TvRemoteService.buildSubnetCandidates(lastKnownBaseUrl);
    const step = Math.max(4, batchSize);
    for (let i = 0; i < candidates.length; i += step) {
      const slice = candidates.slice(i, i + step);
      const found = await TvRemoteService.probeLanTv(slice, fetchImpl, probeTimeoutMs);
      if (found) {
        return found;
      }
    }
    return null;
  }

  /**
   * Extracts a 128-bit QR pairing secret from `?pair=...` on a URL or query string.
   */
  public static extractPairSecretFromUrl(searchOrUrl: string): string {
    const raw = (searchOrUrl || '').trim();
    if (!raw) return '';
    const qIdx = raw.indexOf('?');
    const query = qIdx >= 0 ? raw.slice(qIdx + 1).split('#')[0] : raw.replace(/^\?/, '').split('#')[0];
    const params = new URLSearchParams(query);
    return (params.get('pair') || '').trim();
  }

  /**
   * Parses any scanned QR code payload (`http://<tv-ip>:8484/?pair=<secret>`,
   * `mytvremote://pair?url=<encoded>&pair=<secret>`, a 6-digit code, or a raw secret)
   * so scanning a QR code automatically discovers the TV endpoint and pairs in one step.
   */
  public static parsePairingQrPayload(rawInput: string): ParsedQrPairingPayload {
    const empty: ParsedQrPairingPayload = {
      baseUrl: '',
      qrSecret: '',
      code: '',
      token: '',
      role: '',
    };
    const raw = (rawInput || '').trim();
    if (!raw) return empty;

    if (/^\d{6}$/.test(raw)) {
      return { ...empty, code: raw };
    }

    const qIdx = raw.indexOf('?');
    const queryPart = qIdx >= 0 ? raw.slice(qIdx + 1).split('#')[0] : '';
    const params = new URLSearchParams(queryPart);
    const pairParam = (params.get('pair') || '').trim();
    const codeParam = (params.get('code') || '').trim();
    const tokenParam = (params.get('token') || '').trim();
    const roleRaw = (params.get('role') || '').trim().toUpperCase();
    const roleParam: RemoteRole | '' =
      roleRaw === 'ADMIN' || roleRaw === 'HOUSEHOLD' || roleRaw === 'GUEST'
        ? (roleRaw as RemoteRole)
        : '';
    const urlParam = (params.get('url') || '').trim();

    if (/^mytvremote:\/\//i.test(raw)) {
      return {
        baseUrl: urlParam ? TvRemoteService.normalizeHostInput(urlParam) : '',
        qrSecret: pairParam,
        code: /^\d{6}$/.test(codeParam) ? codeParam : '',
        token: tokenParam,
        role: roleParam,
      };
    }

    if (/^https?:\/\//i.test(raw)) {
      const originMatch = raw.match(/^(https?:\/\/[^/?#]+)/i);
      const extractedBase = originMatch ? TvRemoteService.normalizeHostInput(originMatch[1]) : '';
      return {
        baseUrl: urlParam ? TvRemoteService.normalizeHostInput(urlParam) : extractedBase,
        qrSecret: pairParam,
        code: /^\d{6}$/.test(codeParam) ? codeParam : '',
        token: tokenParam,
        role: roleParam,
      };
    }

    if (pairParam || tokenParam || codeParam) {
      return {
        baseUrl: urlParam ? TvRemoteService.normalizeHostInput(urlParam) : '',
        qrSecret: pairParam,
        code: /^\d{6}$/.test(codeParam) ? codeParam : '',
        token: tokenParam,
        role: roleParam,
      };
    }

    if (/^[A-Za-z0-9_-]{16,64}$/.test(raw)) {
      return { ...empty, qrSecret: raw };
    }

    return empty;
  }

  /**
   * Detects ISO/IEC 18004 `1:1:3:1:1` QR Finder Patterns in an 8-bit grayscale luminance frame
   * (`width x height`) with horizontal + vertical cross-verification so in-app camera QR pairing
   * succeeds even on Android WebViews where `window.BarcodeDetector` is unavailable.
   */
  public static hasQrFinderPatternInLuma(
    luma: Uint8Array,
    width: number,
    height: number
  ): boolean {
    if (!luma || width < 32 || height < 32 || luma.length < width * height) {
      return false;
    }
    let sum = 0;
    const stepSample = Math.max(1, Math.floor((width * height) / 1024));
    let countSample = 0;
    for (let i = 0; i < width * height; i += stepSample) {
      sum += luma[i];
      countSample++;
    }
    const threshold = countSample > 0 ? Math.floor(sum / countSample) : 128;

    const matches11311 = (counts: number[]): boolean => {
      const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
      if (total < 7) return false;
      const moduleSize = total / 7.0;
      const maxVariance = moduleSize * 0.65;
      return (
        Math.abs(moduleSize - counts[0]) <= maxVariance &&
        Math.abs(moduleSize - counts[1]) <= maxVariance &&
        Math.abs(3.0 * moduleSize - counts[2]) <= 3.0 * maxVariance &&
        Math.abs(moduleSize - counts[3]) <= maxVariance &&
        Math.abs(moduleSize - counts[4]) <= maxVariance
      );
    };

    const verifyVertical = (centerX: number, startY: number, maxModule: number): boolean => {
      const counts = [0, 0, 0, 0, 0];
      let y = startY;
      while (y >= 0 && luma[y * width + centerX] < threshold) {
        counts[2]++;
        y--;
      }
      if (y < 0) return false;
      while (y >= 0 && luma[y * width + centerX] >= threshold && counts[1] <= maxModule) {
        counts[1]++;
        y--;
      }
      if (y < 0 || counts[1] > maxModule) return false;
      while (y >= 0 && luma[y * width + centerX] < threshold && counts[0] <= maxModule) {
        counts[0]++;
        y--;
      }
      y = startY + 1;
      while (y < height && luma[y * width + centerX] < threshold) {
        counts[2]++;
        y++;
      }
      if (y >= height) return false;
      while (y < height && luma[y * width + centerX] >= threshold && counts[3] <= maxModule) {
        counts[3]++;
        y++;
      }
      if (y >= height || counts[3] > maxModule) return false;
      while (y < height && luma[y * width + centerX] < threshold && counts[4] <= maxModule) {
        counts[4]++;
        y++;
      }
      return matches11311(counts);
    };

    const centers: Array<{ x: number; y: number }> = [];
    const rowStep = Math.max(1, Math.floor(height / 64));
    for (let y = 2; y < height - 2; y += rowStep) {
      const counts = [0, 0, 0, 0, 0];
      let state = 0;
      for (let x = 0; x < width; x++) {
        const isDark = luma[y * width + x] < threshold;
        if (isDark) {
          if (state === 1 || state === 3) {
            state++;
          }
          counts[state]++;
        } else {
          if (state === 0) {
            if (counts[0] > 0) {
              state = 1;
              counts[1] = 1;
            }
          } else if (state === 2) {
            state = 3;
            counts[3] = 1;
          } else if (state === 4) {
            if (matches11311(counts)) {
              const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
              const centerX = Math.max(0, Math.min(width - 1, Math.floor(x - counts[4] - counts[3] - counts[2] / 2)));
              if (verifyVertical(centerX, y, Math.ceil(total / 2))) {
                const duplicate = centers.some(
                  (c) => Math.hypot(c.x - centerX, c.y - y) < Math.max(10, total * 0.6)
                );
                if (!duplicate) {
                  centers.push({ x: centerX, y });
                  if (centers.length >= 2) {
                    return true;
                  }
                }
              }
            }
            counts[0] = counts[2];
            counts[1] = counts[3];
            counts[2] = counts[4];
            counts[3] = 1;
            counts[4] = 0;
            state = 3;
          } else {
            counts[state]++;
          }
        }
      }
    }
    return false;
  }

  public setBaseUrl(newBaseUrl: string): void {
    this.baseUrl = TvRemoteService.normalizeBaseUrl(newBaseUrl);
  }

  public getBaseUrl(): string {
    return this.baseUrl;
  }

  public setAuthToken(token: string): void {
    this.authToken = (token || '').trim();
  }

  public getAuthToken(): string {
    return this.authToken;
  }

  public setOnAuthError(
    listener: ((err: RemoteUnauthorizedError | RemoteForbiddenError) => void) | null
  ): void {
    this.onAuthError = listener;
  }

  /**
   * Queries `/api/auth/config` to check current token validity, role, 30-day Guest expiry, and Google OAuth configuration.
   */
  public async getAuthConfig(): Promise<AuthConfigResponse> {
    const response = await this.fetchFn(`${this.baseUrl}/api/auth/config`, this.buildGetInit());
    if (!response.ok) {
      throw new Error(`Failed to fetch auth config (HTTP ${response.status})`);
    }
    return (await response.json()) as AuthConfigResponse;
  }

  /**
   * Triggers the `"New Remote Detected"` modal on the TV (`POST /api/pair/request`) and returns a 5-minute `requestId`.
   */
  public async requestPairing(
    deviceName: string,
    clientId: string = ''
  ): Promise<PairRequestResponse> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/pair/request`,
      this.buildPostInit({
        deviceName: deviceName.trim() || 'Companion Remote',
        clientId: clientId.trim(),
      })
    );
    const body = (await this.safeJson(response)) as Record<string, unknown>;
    if (!response.ok) {
      throw new Error(String(body.message || body.error || `Pairing request failed (${response.status})`));
    }
    return body as unknown as PairRequestResponse;
  }

  /**
   * Polls `/api/pair/poll?requestId=...` so 1-click TV remote approval automatically pairs the waiting phone.
   */
  public async pollPairing(requestId: string): Promise<PairPollResponse> {
    const cleanId = encodeURIComponent(requestId.trim());
    const response = await this.fetchFn(
      `${this.baseUrl}/api/pair/poll?requestId=${cleanId}`,
      { method: 'GET' }
    );
    if (!response.ok) {
      throw new Error(`Pairing poll failed (HTTP ${response.status})`);
    }
    const data = (await response.json()) as PairPollResponse;
    if (data.status === 'approved' && data.token) {
      this.setAuthToken(data.token);
    }
    return data;
  }

  /**
   * Verifies either a 6-digit pairing code OR a scanned QR secret (`POST /api/pair/verify`) and stores the issued token.
   */
  public async verifyPairing(params: {
    code?: string;
    qrSecret?: string;
    requestId?: string;
    clientId?: string;
    qrCameraDetected?: boolean;
    deviceName?: string;
  }): Promise<PairVerifyResponse> {
    const payload: Record<string, unknown> = {
      code: (params.code || '').trim(),
      qrSecret: (params.qrSecret || '').trim(),
      deviceName: (params.deviceName || '').trim(),
    };
    if (params.requestId) payload.requestId = params.requestId.trim();
    if (params.clientId) payload.clientId = params.clientId.trim();
    if (params.qrCameraDetected) payload.qrCameraDetected = true;
    const response = await this.fetchFn(
      `${this.baseUrl}/api/pair/verify`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      }
    );
    const body = (await this.safeJson(response)) as Record<string, unknown>;
    if (!response.ok) {
      throw new Error(String(body.message || body.error || `Invalid pairing code (${response.status})`));
    }
    const verified = body as unknown as PairVerifyResponse;
    if (verified.token) {
      this.setAuthToken(verified.token);
    }
    return verified;
  }

  /**
   * Builds the Supabase Google OAuth URL (`mytvremote://auth` for native APK or `/auth/callback` for mobile browsers).
   */
  public buildGoogleSignInUrl(
    supabaseUrl: string,
    isNativeApp: boolean,
    webOrigin: string = ''
  ): string {
    const cleanSupabase = TvRemoteService.normalizeBaseUrl(supabaseUrl);
    if (!cleanSupabase) {
      throw new Error('Supabase URL is not configured on TV');
    }
    const baseRedirect = isNativeApp
      ? TvRemoteService.NATIVE_AUTH_REDIRECT_URI
      : `${TvRemoteService.normalizeBaseUrl(webOrigin || this.baseUrl)}/auth/callback`;
    return `${cleanSupabase}/auth/v1/authorize?provider=google&redirect_to=${encodeURIComponent(baseRedirect)}`;
  }

  /**
   * Launches the Google Admin Sign-In flow in the external browser / top-level window.
   */
  public startGoogleSignIn(
    supabaseUrl: string,
    isNativeApp: boolean,
    navigateFn?: (url: string) => void,
    webOrigin: string = ''
  ): string {
    const url = this.buildGoogleSignInUrl(supabaseUrl, isNativeApp, webOrigin);
    if (navigateFn) {
      navigateFn(url);
    } else if (typeof window !== 'undefined') {
      window.location.assign(url);
    }
    return url;
  }

  /**
   * Exchanges a Supabase Google OAuth `access_token` with the TV (`POST /api/auth/google`) for a permanent local ADMIN token.
   */
  public async exchangeGoogleToken(
    accessToken: string,
    deviceName: string = 'Admin Phone'
  ): Promise<GoogleAuthResponse> {
    const response = await this.fetchFn(`${this.baseUrl}/api/auth/google`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accessToken: accessToken.trim(),
        deviceName: deviceName.trim() || 'Admin Phone',
      }),
    });
    const body = (await this.safeJson(response)) as Record<string, unknown>;
    if (response.status === 403) {
      throw new RemoteForbiddenError(String(body.error || 'email_not_whitelisted'), 'ADMIN');
    }
    if (!response.ok) {
      throw new Error(String(body.message || body.error || `Google sign-in failed (${response.status})`));
    }
    const authResp = body as unknown as GoogleAuthResponse;
    if (authResp.token) {
      this.setAuthToken(authResp.token);
    }
    return authResp;
  }

  /**
   * Lists all paired Companion Remote devices & guests (`GET /api/admin/devices`, ADMIN role required).
   */
  public async listPairedDevices(): Promise<PairedDeviceInfo[]> {
    const response = await this.fetchFn(`${this.baseUrl}/api/admin/devices`, this.buildGetInit());
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to list paired devices (HTTP ${response.status})`);
    }
    const data = (await response.json()) as { devices?: PairedDeviceInfo[] };
    return Array.isArray(data.devices) ? data.devices : [];
  }

  /**
   * Immediately kicks/revokes a paired device or lost phone (`POST /api/admin/devices`, ADMIN role required).
   */
  public async revokeDevice(deviceId: string): Promise<PairedDeviceInfo[]> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/admin/devices`,
      this.buildPostInit({ action: 'revoke', deviceId: deviceId.trim() })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to revoke device (HTTP ${response.status})`);
    }
    const data = (await response.json()) as { devices?: PairedDeviceInfo[] };
    return Array.isArray(data.devices) ? data.devices : [];
  }

  /**
   * Changes a paired device's role (`GUEST` <-> `HOUSEHOLD` <-> `ADMIN`, ADMIN role required).
   */
  public async setDeviceRole(deviceId: string, role: RemoteRole): Promise<PairedDeviceInfo[]> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/admin/devices`,
      this.buildPostInit({ action: 'set_role', deviceId: deviceId.trim(), role })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to update device role (HTTP ${response.status})`);
    }
    const data = (await response.json()) as { devices?: PairedDeviceInfo[] };
    return Array.isArray(data.devices) ? data.devices : [];
  }

  /**
   * Creates a 5-minute 6-digit + QR invite for a specific role (`GUEST`, `HOUSEHOLD`, or `ADMIN`).
   */
  public async createInvite(role: RemoteRole = 'GUEST'): Promise<AdminInviteResponse> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/admin/devices`,
      this.buildPostInit({ action: 'create_invite', role })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to create invite (HTTP ${response.status})`);
    }
    return (await response.json()) as AdminInviteResponse;
  }

  /**
   * Fetches the current TV status, active user profile, Smart Home device states, and Shared Reels.
   */
  public async getStatus(): Promise<TvStatus> {
    const response = await this.fetchFn(`${this.baseUrl}/api/status`, this.buildGetInit());
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to fetch TV status (HTTP ${response.status})`);
    }
    return (await response.json()) as TvStatus;
  }

  /**
   * Sends a D-Pad, volume, or navigation key press to the TV Launcher.
   */
  public async sendKey(key: RemoteKeyName): Promise<boolean> {
    return this.postOk('/api/key', { key });
  }

  /**
   * Sends touchpad scroll deltas to the TV Launcher.
   */
  public async sendScroll(dx: number, dy: number): Promise<boolean> {
    return this.postOk('/api/scroll', {
      dx: Math.round(dx),
      dy: Math.round(dy),
    });
  }

  /**
   * Copies text from the mobile device to the TV clipboard and optionally triggers AI Search.
   */
  public async sendText(text: string, submit: boolean = false): Promise<boolean> {
    const trimmed = text.trim();
    if (!trimmed) {
      return false;
    }
    return this.postOk('/api/text', { text: trimmed, submit });
  }

  /**
   * Sends a voice command transcript from the phone's microphone to the TV.
   */
  public async sendVoiceCommand(transcript: string): Promise<boolean> {
    const trimmed = transcript.trim();
    if (!trimmed) {
      return false;
    }
    return this.postOk('/api/voice', { transcript: trimmed });
  }

  /**
   * Queries Gemini AI recommendations via the TV's encrypted API key (`POST /api/ai`).
   */
  public async askGeminiAi(query: string, openOnTv: boolean = false): Promise<AiQueryResult> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/ai`,
      this.buildPostInit({ query: query.trim(), openOnTv })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Gemini AI query failed (HTTP ${response.status})`);
    }
    return (await response.json()) as AiQueryResult;
  }

  /**
   * Broadcasts/casts a media or Reel/TikTok URL (`http://`, `https://`, `stremio://`) to the TV.
   */
  public async castMedia(
    url: string,
    title: string = 'Shared Media',
    sender: string = 'Remote',
    saveReel: boolean = true
  ): Promise<boolean> {
    const trimmedUrl = url.trim();
    if (!trimmedUrl) {
      return false;
    }
    return this.postOk('/api/cast', {
      url: trimmedUrl,
      title: title.trim() || 'Shared Media',
      sender: sender.trim() || 'Remote',
      saveReel,
    });
  }

  /**
   * Toggles a Smart Home device (Sensibo AC, WiZ V2 Lights, Switcher Boiler, Tuya AC).
   */
  public async toggleSmartHomeDevice(deviceId: string): Promise<TvStatus> {
    return this.postForStatus('/api/smarthome', { deviceId, tempDelta: 0 });
  }

  /**
   * Adjusts the target temperature of an AC device (Sensibo or Tuya).
   */
  public async adjustAcTemperature(deviceId: string, tempDelta: number): Promise<TvStatus> {
    return this.postForStatus('/api/smarthome', { deviceId, tempDelta });
  }

  /**
   * Sets full AC parameters (power, mode, target temperature, and fan speed) on Sensibo or Tuya AC.
   */
  public async setAcParameters(
    deviceId: string,
    on: boolean,
    acMode: string,
    targetTemp: number,
    fanLevel: string
  ): Promise<TvStatus> {
    return this.postForStatus('/api/smarthome', {
      action: 'ac_params',
      deviceId,
      on,
      acMode,
      targetTemp,
      fanLevel,
    });
  }

  /**
   * Sets full WiZ V2 light parameters (power, dimming brightness percentage, and color temperature in Kelvin).
   */
  public async setLightParameters(
    deviceId: string,
    on: boolean,
    brightness: number,
    colorTempK: number
  ): Promise<TvStatus> {
    return this.postForStatus('/api/smarthome', {
      action: 'light_params',
      deviceId,
      on,
      brightness,
      colorTempK,
    });
  }

  /**
   * Turns on the Switcher Water Boiler with a specified auto-off timer duration in minutes.
   */
  public async setBoilerTimer(deviceId: string, timerMinutes: number): Promise<TvStatus> {
    return this.postForStatus('/api/smarthome', {
      action: 'boiler_timer',
      deviceId,
      timerMinutes,
    });
  }

  /**
   * Executes a multi-device Smart Home routine or triggers dynamic WiZ UDP bulb discovery on the TV's Wi-Fi.
   */
  public async executeSmartHomeRoutine(routineId: SmartHomeRoutineId): Promise<TvStatus> {
    return this.postForStatus('/api/smarthome', {
      action: 'routine',
      routineId,
    });
  }

  /**
   * Switches the active TV user profile.
   */
  public async switchUserProfile(username: string): Promise<TvStatus> {
    return this.postForStatus('/api/user', {
      action: 'switch',
      username: username.trim().toLowerCase(),
    });
  }

  /**
   * Adds or updates a user profile on the TV and syncs it to Supabase.
   */
  public async addUserProfile(displayName: string, traktUsername: string = ''): Promise<TvStatus> {
    return this.postForStatus('/api/user', {
      action: 'add',
      displayName: displayName.trim(),
      traktUsername: traktUsername.trim(),
    });
  }

  /**
   * Launches an installed Android TV application by package name.
   */
  public async launchApp(packageName: string): Promise<boolean> {
    const trimmed = packageName.trim();
    if (!trimmed) {
      return false;
    }
    return this.postOk('/api/app', { pkg: trimmed });
  }

  /**
   * Switches between the custom MyTV Launcher ("mytv"), the TV's factory Native Launcher ("original"),
   * or opens the on-TV Settings screen ("settings").
   */
  public async setLauncherMode(mode: 'mytv' | 'original' | 'settings'): Promise<boolean> {
    return this.postOk('/api/launcher_mode', { mode });
  }

  /**
   * Saves an optional GitHub Personal Access Token (Contents: Read-only) into encrypted TV Secrets
   * and triggers an immediate OTA self-update check against the TV's configured GitHub repository.
   */
  public async triggerSelfUpdate(
    githubToken: string = '',
    githubRepo: string = ''
  ): Promise<boolean> {
    return this.postOk('/api/update', {
      githubToken: githubToken.trim(),
      githubRepo: githubRepo.trim(),
    });
  }

  /**
   * Fetches the current Gaming Zone laptop status (awake, locked, HDMI vs. Wi-Fi, Sunshine state, and games).
   */
  public async getGamingStatus(): Promise<GamingStatus> {
    const response = await this.fetchFn(`${this.baseUrl}/api/gaming`, this.buildGetInit());
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to fetch Gaming Zone status (HTTP ${response.status})`);
    }
    return (await response.json()) as GamingStatus;
  }

  /**
   * Unlocks the Windows Gaming PC wirelessly (`POST /api/gaming` `action: "unlock"`, ADMIN role required).
   */
  public async unlockLaptop(pin: string): Promise<GamingStatus> {
    const cleanPin = pin.trim();
    if (!cleanPin) {
      throw new Error('Windows unlock PIN is required');
    }
    const response = await this.fetchFn(
      `${this.baseUrl}/api/gaming`,
      this.buildPostInit({ action: 'unlock', pin: cleanPin })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to unlock gaming PC (HTTP ${response.status})`);
    }
    return (await response.json()) as GamingStatus;
  }

  /**
   * Wakes the gaming laptop, unlocks Windows if an alphanumeric PIN is provided, and launches a game.
   */
  public async launchGame(gameId: string, pin: string = ''): Promise<GamingStatus> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/gaming`,
      this.buildPostInit({
        action: 'launch',
        gameId: gameId.trim() || 'steam_bigpicture',
        pin: pin.trim(),
      })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to launch game (HTTP ${response.status})`);
    }
    return (await response.json()) as GamingStatus;
  }

  /**
   * Hides or re-shows a game in the Gaming Zone picker.
   */
  public async setGameHidden(gameId: string, hidden: boolean): Promise<GamingStatus> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/gaming`,
      this.buildPostInit({
        action: 'hide',
        gameId: gameId.trim(),
        hidden,
      })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to update game visibility (HTTP ${response.status})`);
    }
    return (await response.json()) as GamingStatus;
  }

  /**
   * Sends a Wake-on-LAN magic packet + S0 Modern Standby wake signal to the gaming laptop.
   */
  public async wakeGamingPc(): Promise<GamingStatus> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/gaming`,
      this.buildPostInit({ action: 'wake' })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to wake gaming PC (HTTP ${response.status})`);
    }
    return (await response.json()) as GamingStatus;
  }

  /**
   * Forces a live sync of the game catalog with the Laptop Gaming Agent (:8485).
   */
  public async syncGamingLibrary(): Promise<GamingStatus> {
    const response = await this.fetchFn(
      `${this.baseUrl}/api/gaming`,
      this.buildPostInit({ action: 'refresh' })
    );
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`Failed to sync gaming library (HTTP ${response.status})`);
    }
    return (await response.json()) as GamingStatus;
  }

  /**
   * Returns the direct LAN download URL for the standalone Android Companion Remote APK (`MyTV-Remote.apk`).
   */
  public getAndroidApkDownloadUrl(): string {
    return `${this.baseUrl}/MyTV-Remote.apk`;
  }

  private buildGetInit(): FetchInitLike {
    if (!this.authToken) {
      return { method: 'GET' };
    }
    return {
      method: 'GET',
      headers: { Authorization: `Bearer ${this.authToken}` },
    };
  }

  private buildPostInit(payload: Record<string, unknown>): FetchInitLike {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.authToken) {
      headers.Authorization = `Bearer ${this.authToken}`;
    }
    return {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
    };
  }

  private async safeJson(response: FetchResponseLike): Promise<unknown> {
    try {
      return await response.json();
    } catch {
      return {};
    }
  }

  private async throwIfUnauthorizedOrForbidden(response: FetchResponseLike): Promise<void> {
    if (response.status === 401) {
      const data = (await this.safeJson(response)) as Record<string, unknown>;
      const err = new RemoteUnauthorizedError(
        String(data.error || 'unauthorized'),
        data.canRepair !== false
      );
      if (this.onAuthError) this.onAuthError(err);
      throw err;
    }
    if (response.status === 403) {
      const data = (await this.safeJson(response)) as Record<string, unknown>;
      const err = new RemoteForbiddenError(
        String(data.error || 'insufficient_role'),
        String(data.requiredRole || '')
      );
      if (this.onAuthError) this.onAuthError(err);
      throw err;
    }
  }

  private async postOk(path: string, payload: Record<string, unknown>): Promise<boolean> {
    try {
      const response = await this.fetchFn(`${this.baseUrl}${path}`, this.buildPostInit(payload));
      if (response.status === 401 || response.status === 403) {
        await this.throwIfUnauthorizedOrForbidden(response).catch(() => {});
        return false;
      }
      return response.ok;
    } catch {
      return false;
    }
  }

  private async postForStatus(path: string, payload: Record<string, unknown>): Promise<TvStatus> {
    const response = await this.fetchFn(`${this.baseUrl}${path}`, this.buildPostInit(payload));
    await this.throwIfUnauthorizedOrForbidden(response);
    if (!response.ok) {
      throw new Error(`TV request failed on ${path} (HTTP ${response.status})`);
    }
    return (await response.json()) as TvStatus;
  }
}
