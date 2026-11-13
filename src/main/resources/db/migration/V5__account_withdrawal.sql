-- The account and unique Kakao subject remain until every cleanup stage has finished.
CREATE TABLE app.account_withdrawals (
    account_id uuid PRIMARY KEY REFERENCES app.accounts(id),
    stage text NOT NULL DEFAULT 'UNLINK_PENDING',
    lease_token uuid,
    lease_expires_at timestamptz,
    retry_at timestamptz NOT NULL,
    attempt_count bigint NOT NULL DEFAULT 0,
    error_code varchar(64),
    CONSTRAINT ck_account_withdrawals_stage
        CHECK (stage IN ('UNLINK_PENDING', 'CLEANUP_PENDING', 'FILES_PENDING')),
    CONSTRAINT ck_account_withdrawals_lease
        CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT ck_account_withdrawals_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_account_withdrawals_error_code
        CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

CREATE INDEX idx_account_withdrawals_due ON app.account_withdrawals(retry_at, account_id);
CREATE INDEX idx_account_withdrawals_lease ON app.account_withdrawals(lease_expires_at)
    WHERE lease_token IS NOT NULL;

CREATE TABLE app.account_withdrawal_files (
    account_id uuid NOT NULL REFERENCES app.account_withdrawals(account_id),
    storage_kind text NOT NULL,
    storage_key text NOT NULL,
    done boolean NOT NULL DEFAULT false,
    PRIMARY KEY (storage_kind, storage_key),
    CONSTRAINT ck_account_withdrawal_files_storage_kind CHECK (storage_kind IN ('PROFILE', 'OBSERVATION')),
    CONSTRAINT ck_account_withdrawal_files_storage_key CHECK (length(btrim(storage_key)) > 0)
);

CREATE INDEX idx_account_withdrawal_files_pending ON app.account_withdrawal_files(account_id)
    WHERE done = false;
