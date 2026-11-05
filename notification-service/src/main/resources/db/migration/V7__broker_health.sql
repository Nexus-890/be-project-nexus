CREATE TYPE health_status AS ENUM ('UP', 'DOWN');

CREATE TABLE broker_health (
    name              varchar(50)   PRIMARY KEY,
    status            health_status NOT NULL,
    last_checked_at   timestamptz   NOT NULL,
    last_failure_at   timestamptz,
    last_recovered_at timestamptz
);
