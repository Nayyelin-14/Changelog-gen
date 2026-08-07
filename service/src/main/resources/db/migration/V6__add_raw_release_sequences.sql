-- Panache's default ID generator expects a backing sequence named <table>_SEQ — V5 used an
-- IDENTITY column instead, which works for inserts but fails Hibernate's schema validation since
-- no such sequence exists. These match exactly what Hibernate itself reported as missing.
CREATE SEQUENCE raw_release_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE release_pr_SEQ START WITH 1 INCREMENT BY 50;
