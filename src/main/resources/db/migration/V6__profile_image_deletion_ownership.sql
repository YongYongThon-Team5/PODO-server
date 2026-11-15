-- Keep provenance after orphan cleanup removes image metadata. No FK is intentional:
-- enqueue locks images first, while withdrawal locks the account first.
ALTER TABLE app.profile_image_deletions ADD COLUMN account_id uuid;

UPDATE app.profile_image_deletions pending
SET account_id = image.account_id
FROM app.profile_images image
WHERE image.id = pending.image_id AND image.storage_key = pending.storage_key;

-- Unknown legacy owners remain NULL and block withdrawal finalization until generic cleanup drains them.
CREATE INDEX idx_profile_image_deletions_account ON app.profile_image_deletions(account_id);
