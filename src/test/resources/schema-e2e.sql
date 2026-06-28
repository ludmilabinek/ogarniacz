-- Spring Security PersistentTokenBasedRememberMeServices writes to this table on every
-- login. In prod the schema is owned by an external migration; under the e2e profile
-- (H2 in-memory) we create it here so signup/login succeed without manual DDL.
CREATE TABLE IF NOT EXISTS persistent_logins (
    username  VARCHAR(64)  NOT NULL,
    series    VARCHAR(64)  PRIMARY KEY,
    token     VARCHAR(64)  NOT NULL,
    last_used TIMESTAMP    NOT NULL
);
