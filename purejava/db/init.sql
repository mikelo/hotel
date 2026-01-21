
-- CREATE TABLE IF NOT EXISTS city (
--   id SERIAL PRIMARY KEY,
--   name TEXT NOT NULL,
--   country TEXT,
--   latitude DOUBLE PRECISION NOT NULL,
--   longitude DOUBLE PRECISION NOT NULL,
--   timezone TEXT,
--   population BIGINT
-- );

CREATE TABLE IF NOT EXISTS city_temperature (
  id BIGSERIAL PRIMARY KEY,
  -- city_id INTEGER NOT NULL REFERENCES city(id) ON DELETE CASCADE,
  city TEXT NOT NULL,
  ts TIMESTAMPTZ NOT NULL,
  temperature_c DOUBLE PRECISION,
  source TEXT DEFAULT 'open-meteo',
  current BOOLEAN DEFAULT FALSE,
  inserted_at TIMESTAMPTZ DEFAULT NOW()
);

-- CREATE UNIQUE INDEX IF NOT EXISTS uq_city_temperature_ci_ts
--   ON city_temperature(city_id, ts);

CREATE UNIQUE INDEX IF NOT EXISTS uq_city_temperature_ci_ts
  ON city_temperature(city, ts);
