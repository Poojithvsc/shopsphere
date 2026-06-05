-- Phase 16: a product may have one uploaded image, stored in S3 under this key (e.g. <id>.png).
-- Nullable: most products have no image. The bytes live in S3, not the database — this column only
-- records where to find them, so GET /products can mint a presigned read URL without an existence check.
ALTER TABLE catalog.products ADD COLUMN image_key VARCHAR(255);
