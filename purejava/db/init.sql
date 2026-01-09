
CREATE TABLE IF NOT EXISTS city (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  country TEXT,
  latitude DOUBLE PRECISION NOT NULL,
  longitude DOUBLE PRECISION NOT NULL,
  timezone TEXT,
  population BIGINT
);

CREATE TABLE IF NOT EXISTS city_temperature (
  id BIGSERIAL PRIMARY KEY,
  city_id INTEGER NOT NULL REFERENCES city(id) ON DELETE CASCADE,
  ts TIMESTAMPTZ NOT NULL,
  temperature_c DOUBLE PRECISION,
  source TEXT DEFAULT 'open-meteo',
  current BOOLEAN DEFAULT FALSE,
  inserted_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_city_temperature_city_ts ON city_temperature(city_id, ts);
CREATE INDEX IF NOT EXISTS idx_city_name ON city(name);