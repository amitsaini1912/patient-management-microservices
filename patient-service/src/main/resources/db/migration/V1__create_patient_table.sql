CREATE TABLE patient (
                         id              UUID         PRIMARY KEY,
                         name            VARCHAR(255) NOT NULL,
                         email           VARCHAR(255) NOT NULL UNIQUE,
                         address         VARCHAR(255) NOT NULL,
                         date_of_birth   DATE         NOT NULL,
                         registered_date DATE         NOT NULL
);