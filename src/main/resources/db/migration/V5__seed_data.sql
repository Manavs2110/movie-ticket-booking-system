-- Seed data for local runs and demos.
-- admin@moviebooking.com / Admin@123   ·   demo@moviebooking.com / Customer@123
INSERT INTO app_user (email, password_hash, full_name, role) VALUES
  ('admin@moviebooking.com', '$2a$10$LWJtcpM6dhkWf0DiHWUCrOHV9iUZwh0AqFn0K4AF/Bngoolz55ece', 'Platform Admin', 'ADMIN'),
  ('demo@moviebooking.com',  '$2a$10$CqiqbNHqPD4SGI.KS1It2uIcCJuExgDK/OeVr1kf/KoXTKrmn1sva', 'Demo Customer',  'CUSTOMER');

INSERT INTO refund_policy (name, is_default, rules) VALUES
  ('Standard', true,  '[{"minHoursBefore":24,"refundPercent":100},{"minHoursBefore":4,"refundPercent":50},{"minHoursBefore":0,"refundPercent":0}]'),
  ('Flexible', false, '[{"minHoursBefore":2,"refundPercent":100},{"minHoursBefore":0,"refundPercent":25}]');

INSERT INTO city (name, state) VALUES ('Bengaluru', 'Karnataka'), ('Mumbai', 'Maharashtra');

INSERT INTO theater (city_id, name, address, latitude, longitude, refund_policy_id) VALUES
  ((SELECT id FROM city WHERE name = 'Bengaluru'), 'PVR Orion Mall', 'Brigade Gateway, Rajajinagar, Bengaluru', 13.011000, 77.555000, NULL),
  ((SELECT id FROM city WHERE name = 'Bengaluru'), 'INOX Garuda',    'Magrath Road, Ashok Nagar, Bengaluru',     12.970000, 77.609000,
     (SELECT id FROM refund_policy WHERE name = 'Flexible')),
  ((SELECT id FROM city WHERE name = 'Mumbai'),    'Cinepolis Andheri', 'Fun Republic, Andheri West, Mumbai',    19.136000, 72.829000, NULL);

INSERT INTO screen (theater_id, name)
SELECT t.id, s.name FROM theater t CROSS JOIN (VALUES ('Screen 1'), ('Screen 2')) AS s(name);

-- 10 rows x 12 seats per screen: rows A-H REGULAR, I-J PREMIUM
INSERT INTO seat (screen_id, row_label, seat_number, seat_type)
SELECT sc.id, chr(64 + r), n, CASE WHEN r >= 9 THEN 'PREMIUM' ELSE 'REGULAR' END
FROM screen sc, generate_series(1, 10) AS r, generate_series(1, 12) AS n;

INSERT INTO movie (title, description, duration_minutes, language, genres, certificate, release_date, cast_members) VALUES
  ('Starfall',         'A salvage crew finds a signal from a ship lost for a century.', 150, 'English', 'Sci-Fi,Adventure', 'UA', DATE '2026-09-01', 'Asha Rao, Leo Grant'),
  ('The Last Monsoon', 'Three generations of a family wait out one final storm.',       135, 'Hindi',   'Drama,Family',     'U',  DATE '2026-08-15', 'Kabir Mehta, Nisha Iyer'),
  ('Chai & Chaos',     'Two rival tea stalls, one street, zero chill.',                 120, 'Hindi',   'Comedy,Romance',   'UA', DATE '2026-09-12', 'Rohan Das, Meera Kapoor'),
  ('Neon Circuit',     'A getaway driver races through a city that never switches off.',165, 'English', 'Action,Thriller',  'A',  DATE '2026-09-20', 'Maya Chen, Arjun Varma');

-- Shows for the next 14 days (IST): 4 slots per screen per day, movies rotated across screens and slots.
-- Longest movie (165 min) + 15 min cleaning fits inside the 4-hour gap between slots.
-- Prices: day shows ₹220 regular / ₹350 premium, evening shows ₹300 / ₹500; weekend multiplier ×1.25 on every show.
INSERT INTO show (movie_id, screen_id, start_time, end_time, regular_price, premium_price, weekend_multiplier)
SELECT m.id,
       sc.id,
       slot_start,
       slot_start + make_interval(mins => m.duration_minutes + 15),
       CASE WHEN slot.h >= 18 THEN 300.00 ELSE 220.00 END,
       CASE WHEN slot.h >= 18 THEN 500.00 ELSE 350.00 END,
       1.25
FROM screen sc
CROSS JOIN generate_series(0, 13) AS d
CROSS JOIN (VALUES (0, 10), (1, 14), (2, 18), (3, 22)) AS slot(idx, h)
CROSS JOIN LATERAL (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') + make_interval(days => d, hours => slot.h))
           AT TIME ZONE 'Asia/Kolkata' AS slot_start
) st
JOIN LATERAL (
  SELECT id, duration_minutes FROM movie ORDER BY id OFFSET ((sc.id + slot.idx) % 4) LIMIT 1
) m ON true;

INSERT INTO discount_code (code, type, value, max_discount, min_order, valid_from, valid_to, usage_limit, per_user_limit) VALUES
  ('FIRST50',   'FLAT',     50.00, NULL,   200.00, now() - interval '1 day', now() + interval '365 days', 1000, 1),
  ('WEEKEND20', 'PERCENT',  20.00, 150.00, 300.00, now() - interval '1 day', now() + interval '365 days', NULL, 3),
  ('EXPIRED10', 'PERCENT',  10.00, NULL,     0.00, now() - interval '30 days', now() - interval '1 day', NULL, 1);
