-- B-13b: background order history import (ADR-0015). HISTORY is a watermark that only moves back, and each history
-- chunk is a sync_check of its own kind, which Integration Health ignores.
ALTER TABLE sync_watermark DROP CONSTRAINT sync_watermark_stream_check;
ALTER TABLE sync_watermark ADD CONSTRAINT sync_watermark_stream_check
    CHECK (stream IN ('PRODUCTS', 'ORDERS', 'HISTORY'));

ALTER TABLE sync_check DROP CONSTRAINT sync_check_kind_check;
ALTER TABLE sync_check ADD CONSTRAINT sync_check_kind_check CHECK (kind IN ('POLL', 'RECONCILE', 'HISTORY'));
