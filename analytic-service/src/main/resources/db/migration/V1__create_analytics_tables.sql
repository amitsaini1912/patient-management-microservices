-- Analytics store (OLAP). Read-optimized copy of the claims data.

CREATE TABLE patient (
                         id    UUID         PRIMARY KEY,
                         name  VARCHAR(255) NOT NULL,
                         email VARCHAR(255) NOT NULL
);

CREATE TABLE visit (
                       id         UUID         PRIMARY KEY,
                       patient_id UUID         NOT NULL,
                       visit_date DATE         NOT NULL,
                       department VARCHAR(100) NOT NULL
);

CREATE TABLE claim (
                       id           UUID          PRIMARY KEY,
                       visit_id     UUID          NOT NULL,
                       claim_amount NUMERIC(10,2) NOT NULL,
                       paid_amount  NUMERIC(10,2) NOT NULL,
                       status       VARCHAR(20)   NOT NULL
);