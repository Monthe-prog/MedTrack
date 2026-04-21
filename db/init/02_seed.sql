-- =============================================================================
-- MedTrack – Seed Data
-- File    : db/init/02_seed.sql
-- Runs automatically after 01_schema.sql on first `docker-compose up`
-- =============================================================================

-- Roles are already inserted in 01_schema.sql via INSERT INTO roles.
-- This file seeds sample medications from openFDA for dev/test use.

-- ---------------------------------------------------------------------------
-- Sample medications (common drugs for development/demo)
-- ---------------------------------------------------------------------------
INSERT INTO medications (id, openfda_id, brand_name, generic_name, manufacturer, dosage_form, strength, route)
VALUES
    (gen_random_uuid(), 'NDA050614', 'Amoxil',      'amoxicillin',          'GSK',        'capsule', '500 mg',  'oral'),
    (gen_random_uuid(), 'NDA018965', 'Tylenol',     'acetaminophen',        'J&J',        'tablet',  '500 mg',  'oral'),
    (gen_random_uuid(), 'NDA020711', 'Lipitor',     'atorvastatin calcium', 'Pfizer',     'tablet',  '10 mg',   'oral'),
    (gen_random_uuid(), 'NDA021400', 'Nexium',      'esomeprazole',         'AstraZeneca','capsule', '40 mg',   'oral'),
    (gen_random_uuid(), 'NDA206352', 'Jardiance',   'empagliflozin',        'Boehringer', 'tablet',  '10 mg',   'oral'),
    (gen_random_uuid(), 'NDA022334', 'Crestor',     'rosuvastatin calcium', 'AstraZeneca','tablet',  '20 mg',   'oral'),
    (gen_random_uuid(), 'NDA019831', 'Zithromax',   'azithromycin',         'Pfizer',     'tablet',  '250 mg',  'oral'),
    (gen_random_uuid(), 'NDA020520', 'Glucophage',  'metformin',            'BMS',        'tablet',  '850 mg',  'oral'),
    (gen_random_uuid(), 'NDA021973', 'Diovan',      'valsartan',            'Novartis',   'tablet',  '160 mg',  'oral'),
    (gen_random_uuid(), 'NDA022233', 'Ventolin',    'albuterol',            'GSK',        'inhaler', '90 mcg',  'inhalation')
ON CONFLICT (openfda_id) DO NOTHING;
