CREATE TABLE discount_code (
  id             BIGSERIAL PRIMARY KEY,
  code           VARCHAR(30) NOT NULL UNIQUE,
  type           VARCHAR(10) NOT NULL CHECK (type IN ('FLAT','PERCENT')),
  value          NUMERIC(10,2) NOT NULL CHECK (value > 0),
  max_discount   NUMERIC(10,2) NULL,
  min_order      NUMERIC(10,2) NOT NULL DEFAULT 0,
  valid_from     TIMESTAMPTZ NOT NULL,
  valid_to       TIMESTAMPTZ NOT NULL,
  usage_limit    INT NULL,
  used_count     INT NOT NULL DEFAULT 0,
  per_user_limit INT NOT NULL DEFAULT 1,
  active         BOOLEAN NOT NULL DEFAULT true,
  CHECK (type <> 'PERCENT' OR value <= 100),
  CHECK (usage_limit IS NULL OR used_count <= usage_limit),
  CHECK (valid_to > valid_from)
);

-- rules: JSON array of {"minHoursBefore": 24, "refundPercent": 100}; the app validates and sorts them
CREATE TABLE refund_policy (
  id         BIGSERIAL PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT false,
  rules      JSONB NOT NULL CHECK (jsonb_typeof(rules) = 'array')
);
CREATE UNIQUE INDEX ux_refund_policy_default ON refund_policy(is_default) WHERE is_default;

ALTER TABLE theater ADD CONSTRAINT fk_theater_policy
  FOREIGN KEY (refund_policy_id) REFERENCES refund_policy(id);
