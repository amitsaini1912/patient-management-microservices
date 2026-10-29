-- One billing account per patient. patient_id is UNIQUE so a retried gRPC call
-- from patient-service can never create a second account for the same patient.
CREATE TABLE billing_account (
    id         UUID         PRIMARY KEY,
    patient_id UUID         NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    currency   VARCHAR(3)   NOT NULL DEFAULT 'INR',
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
