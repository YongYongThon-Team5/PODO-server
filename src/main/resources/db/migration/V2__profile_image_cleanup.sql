CREATE TABLE app.profile_image_deletions (
    image_id uuid PRIMARY KEY,
    storage_key text NOT NULL,
    retry_at timestamptz NOT NULL
);

CREATE INDEX idx_profile_image_deletions_retry ON app.profile_image_deletions (retry_at, image_id);
CREATE INDEX idx_profile_images_orphaned ON app.profile_images (orphaned_at, id) WHERE orphaned_at IS NOT NULL;
CREATE INDEX idx_profile_images_storage_key ON app.profile_images USING hash (storage_key);
CREATE INDEX idx_child_profiles_profile_image_id ON app.child_profiles (profile_image_id) WHERE profile_image_id IS NOT NULL;
