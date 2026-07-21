CREATE TABLE visit (
                       id         UUID         PRIMARY KEY,
                       patient_id UUID         NOT NULL REFERENCES patient(id) ON DELETE CASCADE,
                       visit_date DATE         NOT NULL,
                       department VARCHAR(100) NOT NULL
);