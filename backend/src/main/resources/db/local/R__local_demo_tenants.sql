-- Local-only demo data (profile "local"). Never on the default Flyway locations.
-- Organization "Demo" with two stores; owner-a may access only Store A, owner-b only Store B.
INSERT INTO organization (id, name) VALUES
    ('00000000-0000-4000-8000-000000000001', 'Demo')
ON CONFLICT (id) DO NOTHING;

INSERT INTO store (id, organization_id, name, currency, timezone) VALUES
    ('00000000-0000-4000-8000-00000000000a', '00000000-0000-4000-8000-000000000001', 'Sklep A', 'PLN', 'Europe/Warsaw'),
    ('00000000-0000-4000-8000-00000000000b', '00000000-0000-4000-8000-000000000001', 'Sklep B', 'PLN', 'Europe/Warsaw')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, issuer, subject, email, display_name) VALUES
    ('00000000-0000-4000-8000-0000000000a1', 'http://localhost:8180/realms/operator',
     '11111111-1111-4111-8111-111111111111', 'owner-a@shop.local', 'Owner Store A'),
    ('00000000-0000-4000-8000-0000000000b1', 'http://localhost:8180/realms/operator',
     '22222222-2222-4222-8222-222222222222', 'owner-b@shop.local', 'Owner Store B')
ON CONFLICT (issuer, subject) DO NOTHING;

INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-00000000000a',
     '00000000-0000-4000-8000-0000000000a1', 'OWNER'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-00000000000b',
     '00000000-0000-4000-8000-0000000000b1', 'OWNER')
ON CONFLICT DO NOTHING;
