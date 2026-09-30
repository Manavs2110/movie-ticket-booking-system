CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE app_user (
  id            BIGSERIAL PRIMARY KEY,
  email         VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  full_name     VARCHAR(120) NOT NULL,
  role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN','CUSTOMER')),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE city (
  id    BIGSERIAL PRIMARY KEY,
  name  VARCHAR(100) NOT NULL,
  state VARCHAR(100) NOT NULL,
  UNIQUE (name, state)
);

CREATE TABLE theater (
  id               BIGSERIAL PRIMARY KEY,
  city_id          BIGINT NOT NULL REFERENCES city(id),
  name             VARCHAR(150) NOT NULL,
  address          VARCHAR(300) NOT NULL,
  latitude         NUMERIC(9,6) NOT NULL CHECK (latitude  BETWEEN -90  AND 90),
  longitude        NUMERIC(9,6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  refund_policy_id BIGINT NULL,
  active           BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX ix_theater_city   ON theater(city_id);
CREATE INDEX ix_theater_latlng ON theater(latitude, longitude);

CREATE TABLE screen (
  id             BIGSERIAL PRIMARY KEY,
  theater_id     BIGINT NOT NULL REFERENCES theater(id),
  name           VARCHAR(50) NOT NULL,
  layout_version INT NOT NULL DEFAULT 1,
  UNIQUE (theater_id, name)
);

CREATE TABLE seat (
  id          BIGSERIAL PRIMARY KEY,
  screen_id   BIGINT NOT NULL REFERENCES screen(id),
  row_label   VARCHAR(3) NOT NULL,
  seat_number INT NOT NULL CHECK (seat_number > 0),
  seat_type   VARCHAR(10) NOT NULL CHECK (seat_type IN ('REGULAR','PREMIUM')),
  active      BOOLEAN NOT NULL DEFAULT true,
  UNIQUE (screen_id, row_label, seat_number)
);
CREATE INDEX ix_seat_screen ON seat(screen_id);

CREATE TABLE movie (
  id               BIGSERIAL PRIMARY KEY,
  title            VARCHAR(200) NOT NULL,
  description      TEXT,
  duration_minutes INT NOT NULL CHECK (duration_minutes BETWEEN 1 AND 600),
  language         VARCHAR(30) NOT NULL,
  genres           VARCHAR(200) NOT NULL,
  certificate      VARCHAR(5)  NOT NULL CHECK (certificate IN ('U','UA','A')),
  release_date     DATE NOT NULL,
  cast_members     TEXT,
  poster_url       VARCHAR(500),
  trailer_url      VARCHAR(500)
);

CREATE TABLE show (
  id          BIGSERIAL PRIMARY KEY,
  movie_id    BIGINT NOT NULL REFERENCES movie(id),
  screen_id   BIGINT NOT NULL REFERENCES screen(id),
  start_time  TIMESTAMPTZ NOT NULL,
  end_time    TIMESTAMPTZ NOT NULL,
  -- all pricing lives on the show: seat price = (PREMIUM ? premium_price : regular_price)
  --                                              × (starts Sat/Sun IST ? weekend_multiplier : 1)
  regular_price      NUMERIC(10,2) NOT NULL CHECK (regular_price > 0),
  premium_price      NUMERIC(10,2) NOT NULL CHECK (premium_price > 0),
  weekend_multiplier NUMERIC(4,2)  NOT NULL DEFAULT 1.25 CHECK (weekend_multiplier > 0),
  status      VARCHAR(15) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','CANCELLED')),
  version     INT NOT NULL DEFAULT 0,
  CHECK (end_time > start_time),
  CONSTRAINT ex_show_no_overlap EXCLUDE USING gist
    (screen_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status = 'SCHEDULED')
);
CREATE INDEX ix_show_movie_start ON show(movie_id, start_time);
CREATE INDEX ix_show_screen      ON show(screen_id);
