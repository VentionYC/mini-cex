-- Whole token units, bounded to the Solidity uint256 range.
-- Unconstrained NUMERIC avoids rounding before validation.
CREATE DOMAIN token_units AS NUMERIC
    CONSTRAINT token_units_valid CHECK (
        VALUE >= 0
        AND VALUE <=
            115792089237316195423570985008687907853269984665640564039457584007913129639935
        AND VALUE = trunc(VALUE)
    );

CREATE TABLE assets (
    code VARCHAR(16) PRIMARY KEY,
    decimals SMALLINT NOT NULL,

    CONSTRAINT assets_code_format CHECK (
        code ~ '^[A-Z][A-Z0-9]{1,15}$'
    ),
    CONSTRAINT assets_decimals_range CHECK (
        decimals BETWEEN 0 AND 18  
    )
);

INSERT INTO assets (code, decimals)
VALUES ('TETH', 18), ('TUSD', 6);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    asset_code VARCHAR(16) NOT NULL REFERENCES assets(code),
    kind VARCHAR(24) NOT NULL,
    balance token_units NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ledger_accounts_kind CHECK (
        kind IN ('USER_AVAILABLE', 'USER_RESERVED', 'CUSTODY')
    ),
    CONSTRAINT ledger_accounts_owner CHECK (
        (kind = 'CUSTODY' AND user_id IS NULL)
        OR
        (kind IN ('USER_AVAILABLE', 'USER_RESERVED')
            AND user_id IS NOT NULL)
    ),
    CONSTRAINT ledger_accounts_owner_asset_kind_unique
        UNIQUE NULLS NOT DISTINCT (user_id, asset_code, kind),

    CONSTRAINT ledger_accounts_id_asset_unique
        UNIQUE (id, asset_code)
);

-- System accounts start at zero. This does not fund any user.
INSERT INTO ledger_accounts (id, user_id, asset_code, kind)
SELECT gen_random_uuid(), NULL, code, 'CUSTODY'
FROM assets;

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    operation VARCHAR(24) NOT NULL,
    reference VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ledger_transactions_operation CHECK (
        operation IN (
            'DEPOSIT',
            'RESERVE',
            'RELEASE',
            'TRADE',
            'WITHDRAWAL',
            'TEST_FUNDING'
        )
    ),
    CONSTRAINT ledger_transactions_reference_not_blank CHECK (
        btrim(reference) <> ''
    ),
    CONSTRAINT ledger_transactions_reference_unique
        UNIQUE (operation, reference)
);

-- One row represents one balanced debit/credit pair.
CREATE TABLE ledger_postings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    asset_code VARCHAR(16) NOT NULL REFERENCES assets(code),
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    amount token_units NOT NULL,

    CONSTRAINT ledger_postings_positive_amount CHECK (
        amount > 0
    ),
    CONSTRAINT ledger_postings_distinct_accounts CHECK (
        debit_account_id <> credit_account_id
    ),
    CONSTRAINT ledger_postings_debit_asset_fk
        FOREIGN KEY (debit_account_id, asset_code)
        REFERENCES ledger_accounts(id, asset_code),
    CONSTRAINT ledger_postings_credit_asset_fk
        FOREIGN KEY (credit_account_id, asset_code)
        REFERENCES ledger_accounts(id, asset_code)
);

CREATE INDEX ledger_postings_transaction_idx
    ON ledger_postings(transaction_id);

CREATE INDEX ledger_postings_debit_history_idx
    ON ledger_postings(debit_account_id, id);

CREATE INDEX ledger_postings_credit_history_idx
    ON ledger_postings(credit_account_id, id);

-- Completed successful requests, committed together with their effects.
CREATE TABLE idempotency_records (
    user_id UUID NOT NULL REFERENCES users(id),
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status SMALLINT NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, operation, idempotency_key),

    CONSTRAINT idempotency_operation_not_blank CHECK (
        btrim(operation) <> ''
    ),
    CONSTRAINT idempotency_key_not_blank CHECK (
        btrim(idempotency_key) <> ''
    ),
    CONSTRAINT idempotency_request_hash_format CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT idempotency_success_status CHECK (
        response_status BETWEEN 200 AND 299
    )
);

-- Reject ordinary UPDATE/DELETE operations on recorded journal rows.
CREATE FUNCTION reject_ledger_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'Ledger history or account identity cannot be changed: %',
        TG_TABLE_NAME
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER ledger_transactions_immutable
BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_postings_immutable
BEFORE UPDATE OR DELETE ON ledger_postings
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- Balance updates are allowed; reassigning an account is not.
CREATE TRIGGER ledger_accounts_identity_immutable
BEFORE UPDATE OF id, user_id, asset_code, kind, created_at
ON ledger_accounts
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();