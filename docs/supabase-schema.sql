-- ============================================================================
-- SoarTV Optional Cloud Sync & Zero-Knowledge Encrypted Vault Schema (Supabase)
-- Run once in the Supabase SQL Editor (idempotent).
-- ============================================================================

-- 1. Extensions & unexposed private auth configuration schema
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE SCHEMA IF NOT EXISTS private;

CREATE TABLE IF NOT EXISTS private.household_auth_config (
    id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    household_key_sha256 text NOT NULL DEFAULT '',
    allowed_emails text[] NOT NULL DEFAULT '{}',
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO private.household_auth_config (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;

ALTER TABLE private.household_auth_config ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE private.household_auth_config FROM PUBLIC, anon, authenticated;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'private' AND tablename = 'household_auth_config'
          AND policyname = 'service_role_only_household_auth_config'
    ) THEN
        CREATE POLICY service_role_only_household_auth_config
            ON private.household_auth_config FOR ALL TO service_role
            USING (true) WITH CHECK (true);
    END IF;
END $$;

-- 2. Reusable timestamp trigger & Dual-Path RLS authorization helper
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION private.check_household_authorization()
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    cfg record;
    jwt_email text;
    hdr_json jsonb;
    supplied_key text;
    supplied_hash text;
BEGIN
    IF (SELECT auth.role()) = 'service_role' THEN
        RETURN true;
    END IF;

    SELECT household_key_sha256, allowed_emails
      INTO cfg
      FROM private.household_auth_config
     WHERE id = 1;

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    jwt_email := lower(coalesce((SELECT auth.jwt()) ->> 'email', ''));
    IF jwt_email <> '' AND cfg.allowed_emails IS NOT NULL AND jwt_email = ANY(cfg.allowed_emails) THEN
        RETURN true;
    END IF;

    BEGIN
        hdr_json := COALESCE(NULLIF(current_setting('request.headers', true), ''), '{}')::jsonb;
    EXCEPTION WHEN OTHERS THEN
        hdr_json := '{}'::jsonb;
    END;

    supplied_key := coalesce(hdr_json ->> 'x-soartv-household-key', '');
    IF char_length(supplied_key) >= 32 AND coalesce(cfg.household_key_sha256, '') <> '' THEN
        supplied_hash := encode(extensions.digest(convert_to(supplied_key, 'UTF8'), 'sha256'), 'hex');
        IF supplied_hash = lower(cfg.household_key_sha256) THEN
            RETURN true;
        END IF;
    END IF;

    RETURN false;
END;
$$;

REVOKE ALL ON FUNCTION private.check_household_authorization() FROM PUBLIC;
GRANT USAGE ON SCHEMA private TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION private.check_household_authorization() TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.is_authorized_soartv_request()
RETURNS boolean LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
    SELECT private.check_household_authorization();
$$;

GRANT EXECUTE ON FUNCTION public.is_authorized_soartv_request() TO anon, authenticated, service_role;

-- 3. Household tables & Zero-Knowledge Encrypted Key Vault
CREATE TABLE IF NOT EXISTS public.user_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug text NOT NULL UNIQUE,
    display_name text NOT NULL,
    avatar_emoji text NOT NULL DEFAULT '',
    accent_color text NOT NULL DEFAULT '#38BDF8',
    is_default boolean NOT NULL DEFAULT false,
    is_kids boolean NOT NULL DEFAULT false,
    trakt_username text NOT NULL DEFAULT '',
    preferred_Room text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.watchlist_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES public.user_profiles(id) ON DELETE CASCADE,
    media_key text NOT NULL,
    media_type text NOT NULL DEFAULT 'movie',
    title text NOT NULL,
    poster_url text NOT NULL DEFAULT '',
    backdrop_url text NOT NULL DEFAULT '',
    imdb_id text NOT NULL DEFAULT '',
    tmdb_id integer NOT NULL DEFAULT 0,
    year integer NOT NULL DEFAULT 0,
    source_pkg text NOT NULL DEFAULT '',
    deep_link text NOT NULL DEFAULT '',
    added_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_id, media_key)
);

CREATE TABLE IF NOT EXISTS public.watch_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES public.user_profiles(id) ON DELETE CASCADE,
    media_key text NOT NULL,
    media_type text NOT NULL DEFAULT 'movie',
    title text NOT NULL,
    subtitle text NOT NULL DEFAULT '',
    poster_url text NOT NULL DEFAULT '',
    backdrop_url text NOT NULL DEFAULT '',
    season_num integer NOT NULL DEFAULT 0,
    episode_num integer NOT NULL DEFAULT 0,
    position_sec integer NOT NULL DEFAULT 0,
    duration_sec integer NOT NULL DEFAULT 0,
    progress_percent numeric NOT NULL DEFAULT 0
        CONSTRAINT chk_watch_history_progress_percent CHECK (progress_percent >= 0 AND progress_percent <= 100),
    source_pkg text NOT NULL DEFAULT '',
    deep_link text NOT NULL DEFAULT '',
    completed boolean NOT NULL DEFAULT false,
    last_watched_at timestamptz NOT NULL DEFAULT now(),
    watched_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_id, media_key)
);

CREATE TABLE IF NOT EXISTS public.media_votes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES public.user_profiles(id) ON DELETE CASCADE,
    media_key text NOT NULL,
    title text NOT NULL DEFAULT '',
    vote smallint NOT NULL CHECK (vote IN (-1, 1)),
    genres text[] NOT NULL DEFAULT '{}',
    voted_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_id, media_key)
);

CREATE TABLE IF NOT EXISTS public.shared_reels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_name text NOT NULL DEFAULT 'Remote',
    recipient_profile_id uuid REFERENCES public.user_profiles(id) ON DELETE SET NULL,
    platform text NOT NULL DEFAULT 'reel',
    url text NOT NULL DEFAULT '',
    video_url text NOT NULL DEFAULT '',
    title text NOT NULL DEFAULT '',
    caption text NOT NULL DEFAULT '',
    thumbnail_url text NOT NULL DEFAULT '',
    is_watched boolean NOT NULL DEFAULT false,
    watched boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_shared_reels_url_nonempty CHECK (char_length(trim(coalesce(url, video_url, ''))) > 0)
);

CREATE TABLE IF NOT EXISTS public.tv_devices (
    device_slug text PRIMARY KEY,
    device_name text NOT NULL,
    room_name text NOT NULL DEFAULT 'Living Room',
    lan_ip text NOT NULL DEFAULT '',
    remote_port integer NOT NULL DEFAULT 8484
        CONSTRAINT chk_tv_devices_remote_port CHECK (remote_port BETWEEN 1 AND 65535),
    platform text NOT NULL DEFAULT 'android-tv',
    app_version text NOT NULL DEFAULT '1.5',
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.encrypted_vault (
    vault_id text PRIMARY KEY DEFAULT 'primary',
    kdf_algorithm text NOT NULL DEFAULT 'PBKDF2-HMAC-SHA256',
    kdf_salt text NOT NULL,
    kdf_iterations integer NOT NULL DEFAULT 120000 CHECK (kdf_iterations >= 100000),
    cipher_iv text NOT NULL,
    ciphertext text NOT NULL,
    key_count integer NOT NULL DEFAULT 0 CHECK (key_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 4. Sync & timestamp triggers
CREATE OR REPLACE FUNCTION public.sync_shared_reels_columns()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    IF coalesce(NEW.url, '') = '' AND coalesce(NEW.video_url, '') <> '' THEN NEW.url := NEW.video_url;
    ELSIF coalesce(NEW.video_url, '') = '' AND coalesce(NEW.url, '') <> '' THEN NEW.video_url := NEW.url;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.url IS DISTINCT FROM OLD.url THEN NEW.video_url := NEW.url;
        ELSIF NEW.video_url IS DISTINCT FROM OLD.video_url THEN NEW.url := NEW.video_url; END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN
        NEW.watched := coalesce(NEW.watched, false) OR coalesce(NEW.is_watched, false);
        NEW.is_watched := NEW.watched;
    ELSE
        IF NEW.watched IS DISTINCT FROM OLD.watched THEN NEW.is_watched := NEW.watched;
        ELSIF NEW.is_watched IS DISTINCT FROM OLD.is_watched THEN NEW.watched := NEW.is_watched; END IF;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_watchlist_items_columns()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.created_at := coalesce(NEW.created_at, NEW.added_at, now());
        NEW.added_at := coalesce(NEW.added_at, NEW.created_at, now());
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_watch_history_columns()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.watched_at := coalesce(NEW.watched_at, NEW.last_watched_at, now());
        NEW.last_watched_at := coalesce(NEW.last_watched_at, NEW.watched_at, now());
    ELSE
        IF NEW.watched_at IS DISTINCT FROM OLD.watched_at THEN NEW.last_watched_at := NEW.watched_at;
        ELSIF NEW.last_watched_at IS DISTINCT FROM OLD.last_watched_at THEN NEW.watched_at := NEW.last_watched_at; END IF;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE TRIGGER trg_user_profiles_updated_at BEFORE UPDATE ON public.user_profiles FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_watchlist_items_sync BEFORE INSERT OR UPDATE ON public.watchlist_items FOR EACH ROW EXECUTE FUNCTION public.sync_watchlist_items_columns();
CREATE OR REPLACE TRIGGER trg_watch_history_sync BEFORE INSERT OR UPDATE ON public.watch_history FOR EACH ROW EXECUTE FUNCTION public.sync_watch_history_columns();
CREATE OR REPLACE TRIGGER trg_media_votes_updated_at BEFORE UPDATE ON public.media_votes FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_shared_reels_sync BEFORE INSERT OR UPDATE ON public.shared_reels FOR EACH ROW EXECUTE FUNCTION public.sync_shared_reels_columns();
CREATE OR REPLACE TRIGGER trg_tv_devices_updated_at BEFORE UPDATE ON public.tv_devices FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE OR REPLACE TRIGGER trg_encrypted_vault_updated_at BEFORE UPDATE ON public.encrypted_vault FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 5. Indexes & Dual-Path RLS policies
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_profiles_single_default ON public.user_profiles (is_default) WHERE is_default = true;
CREATE INDEX IF NOT EXISTS idx_user_profiles_order ON public.user_profiles (is_default DESC, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_watchlist_profile_created ON public.watchlist_items (profile_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_watch_history_profile_watched ON public.watch_history (profile_id, watched_at DESC);
CREATE INDEX IF NOT EXISTS idx_shared_reels_watched_created ON public.shared_reels (watched, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_shared_reels_recipient ON public.shared_reels (recipient_profile_id);

REVOKE TRUNCATE, REFERENCES, TRIGGER ON
    public.user_profiles, public.watchlist_items, public.watch_history,
    public.media_votes, public.shared_reels, public.tv_devices, public.encrypted_vault
FROM anon, authenticated;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    public.user_profiles, public.watchlist_items, public.watch_history,
    public.media_votes, public.shared_reels, public.tv_devices, public.encrypted_vault
TO anon, authenticated;

ALTER TABLE public.user_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watchlist_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watch_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.media_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.shared_reels ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tv_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.encrypted_vault ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS household_profiles_all ON public.user_profiles;
CREATE POLICY household_profiles_all ON public.user_profiles FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_watchlist_all ON public.watchlist_items;
CREATE POLICY household_watchlist_all ON public.watchlist_items FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_history_all ON public.watch_history;
CREATE POLICY household_history_all ON public.watch_history FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_votes_all ON public.media_votes;
CREATE POLICY household_votes_all ON public.media_votes FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_reels_all ON public.shared_reels;
CREATE POLICY household_reels_all ON public.shared_reels FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_tv_devices_all ON public.tv_devices;
CREATE POLICY household_tv_devices_all ON public.tv_devices FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));

DROP POLICY IF EXISTS household_encrypted_vault_all ON public.encrypted_vault;
CREATE POLICY household_encrypted_vault_all ON public.encrypted_vault FOR ALL TO anon, authenticated
    USING ((SELECT public.is_authorized_soartv_request())) WITH CHECK ((SELECT public.is_authorized_soartv_request()));
