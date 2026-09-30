CREATE TABLE booking (
  id               BIGSERIAL PRIMARY KEY,
  user_id          BIGINT NOT NULL REFERENCES app_user(id),
  show_id          BIGINT NOT NULL REFERENCES show(id),
  status           VARCHAR(20) NOT NULL
                   CHECK (status IN ('HELD','PAYMENT_PENDING','CONFIRMED','CANCELLED','EXPIRED')),
  subtotal         NUMERIC(10,2) NOT NULL,
  discount_amount  NUMERIC(10,2) NOT NULL DEFAULT 0,
  total_amount     NUMERIC(10,2) NOT NULL,
  discount_code_id BIGINT NULL REFERENCES discount_code(id),
  discount_redeemed BOOLEAN NOT NULL DEFAULT false,      -- the code's use was counted at payment
  hold_expires_at  TIMESTAMPTZ NOT NULL,
  -- refund: at most one per booking, so it lives on the booking (all NULL = no refund)
  refund_amount      NUMERIC(10,2),
  refund_percent     INT CHECK (refund_percent BETWEEN 0 AND 100),
  refund_reason      VARCHAR(20) CHECK (refund_reason IN ('CUSTOMER_CANCEL','SHOW_CANCELLED','CONFIRM_FAILED')),
  refund_status      VARCHAR(10) CHECK (refund_status IN ('PENDING','SUCCESS','FAILED')),
  refund_gateway_ref VARCHAR(100),
  refunded_at        TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- one active hold per customer per show (also blocks double-click duplicates)
CREATE UNIQUE INDEX ux_booking_active_hold ON booking(user_id, show_id)
  WHERE status IN ('HELD','PAYMENT_PENDING');
CREATE INDEX ix_booking_user_created ON booking(user_id, created_at DESC, id DESC);
CREATE INDEX ix_booking_show_status  ON booking(show_id, status);
CREATE INDEX ix_booking_discount_use ON booking(discount_code_id, user_id) WHERE discount_redeemed;   -- per-user limit
CREATE INDEX ix_booking_refund       ON booking(refund_status) WHERE refund_status IS NOT NULL;          -- admin refund list

CREATE TABLE booking_item (
  id         BIGSERIAL PRIMARY KEY,
  booking_id BIGINT NOT NULL REFERENCES booking(id),
  seat_id    BIGINT NOT NULL REFERENCES seat(id),
  seat_label VARCHAR(10) NOT NULL,
  seat_type  VARCHAR(10) NOT NULL,
  price      NUMERIC(10,2) NOT NULL,
  UNIQUE (booking_id, seat_id)
);

-- rows exist only for held or sold seats; the primary key is the double-booking guard
CREATE TABLE seat_lock (
  show_id    BIGINT NOT NULL REFERENCES show(id),
  seat_id    BIGINT NOT NULL REFERENCES seat(id),
  booking_id BIGINT NOT NULL REFERENCES booking(id),
  booked     BOOLEAN NOT NULL DEFAULT false,
  held_until TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (show_id, seat_id)
);
CREATE INDEX ix_seat_lock_booking ON seat_lock(booking_id);

CREATE TABLE payment (
  id              BIGSERIAL PRIMARY KEY,
  booking_id      BIGINT NOT NULL REFERENCES booking(id),
  amount          NUMERIC(10,2) NOT NULL,
  status          VARCHAR(20) NOT NULL
                  CHECK (status IN ('PENDING','SUCCESS','FAILED','REFUNDED','PARTIALLY_REFUNDED')),
  idempotency_key VARCHAR(64) NOT NULL,
  gateway_ref     VARCHAR(100),
  failure_reason  VARCHAR(200),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_payment_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX ix_payment_booking ON payment(booking_id);
