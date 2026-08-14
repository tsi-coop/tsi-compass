-- ==========================================
-- MODULE: ASSET TAG & LOCATION
-- Depends on: 01_init.sql (assets)
-- ==========================================

-- asset_tag is the admin-assigned human-readable identifier (e.g.
-- "HQ-LAPTOP-001") — distinct from assets.id, the system-generated UUID
-- primary key. location is free-text (e.g. "HQ - 4th Floor", "Remote").
ALTER TABLE assets ADD COLUMN asset_tag VARCHAR(100);
ALTER TABLE assets ADD COLUMN location VARCHAR(255);

-- Backfill existing rows with a placeholder derived from their UUID (so it's
-- guaranteed unique) before the column is locked down to NOT NULL + UNIQUE.
-- Admins should replace these with real tags.
UPDATE assets SET asset_tag = 'LEGACY-' || id::text WHERE asset_tag IS NULL;

ALTER TABLE assets ALTER COLUMN asset_tag SET NOT NULL;
ALTER TABLE assets ADD CONSTRAINT assets_asset_tag_unique UNIQUE (asset_tag);
