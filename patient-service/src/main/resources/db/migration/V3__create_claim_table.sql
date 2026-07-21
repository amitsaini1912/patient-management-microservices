CREATE TABLE claim (
                       id           UUID          PRIMARY KEY,
                       visit_id     UUID          NOT NULL REFERENCES visit(id) ON DELETE CASCADE,
                       claim_amount NUMERIC(10,2) NOT NULL,
                       paid_amount  NUMERIC(10,2) NOT NULL DEFAULT 0,
                       status       VARCHAR(20)   NOT NULL
                           CHECK (status IN ('PENDING', 'APPROVED', 'DENIED'))
);