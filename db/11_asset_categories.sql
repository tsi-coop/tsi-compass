-- ==========================================
-- MODULE: ASSET CATEGORIES
-- Depends on: 01_init.sql (assets)
-- ==========================================

-- Admin-configurable lookup table, mirroring ticket_categories
-- (06_ticket_categories.sql): categories are meant to change without a
-- migration, so they live in their own table that an ADMIN manages through
-- the console (Platform & Access > Asset Categories), rather than the
-- CHECK-constrained enum this replaces.
CREATE TABLE asset_categories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Seeded with exactly the prior CHECK constraint's values so every existing
-- asset resolves to a row below without changing meaning.
INSERT INTO asset_categories (name) VALUES
    ('APPLICATION'), ('CORE_SERVER'), ('FIREWALL'), ('LAPTOP'), ('DESKTOP'), ('PRINTER'), ('OTHER');

-- Swap assets.category (CHECK-constrained VARCHAR) for assets.category_id
-- (FK), backfilling by name before dropping the old column.
ALTER TABLE assets ADD COLUMN category_id UUID REFERENCES asset_categories(id) ON DELETE RESTRICT;
UPDATE assets a SET category_id = (SELECT id FROM asset_categories WHERE name = a.category);
ALTER TABLE assets ALTER COLUMN category_id SET NOT NULL;
ALTER TABLE assets DROP CONSTRAINT assets_category_check;
ALTER TABLE assets DROP COLUMN category;

CREATE INDEX idx_assets_category_id ON assets(category_id);
