-- PostgreSQL: store instants with a time zone.
-- A plain TIMESTAMP is read in the session time zone, so in a zone with daylight saving two different instants
-- (the repeated hour in autumn) share one stored value and the keyset cursor can repeat or skip rows.
-- Existing values are taken as UTC, which is what the application wrote when the database session ran in UTC.

ALTER TABLE audit_log
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ USING created_at     AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at     TYPE TIMESTAMPTZ USING updated_at     AT TIME ZONE 'UTC',
    ALTER COLUMN deleted_at     TYPE TIMESTAMPTZ USING deleted_at     AT TIME ZONE 'UTC',
    ALTER COLUMN gdpr_erased_at TYPE TIMESTAMPTZ USING gdpr_erased_at AT TIME ZONE 'UTC';
