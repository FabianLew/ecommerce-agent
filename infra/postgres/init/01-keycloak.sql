-- Separate database and role for Keycloak (local only). The application uses the "operator" database.
CREATE ROLE keycloak LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;
