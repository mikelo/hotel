
package com.example;

import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.json.*;

public class App {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String dbHost = env("DB_HOST", "localhost");
        String dbPort = env("DB_PORT", "5432");
        String dbName = env("DB_NAME", "demo");
        String dbUser = env("DB_USER", "demo");
        String dbPass = env("DB_PASSWORD", "demo");

        String citiesCsv = env("CITIES", "Milan,Paris,Berlin");
        List<String> cities = Arrays.stream(citiesCsv.split(","))
                                    .map(String::trim).filter(s -> !s.isEmpty())
                                    .collect(Collectors.toList());

        int pastDays = Integer.parseInt(env("PAST_DAYS", "0")); // include giorni passati nella serie oraria
        String timezone = env("TIMEZONE", "auto");

        String jdbcUrl = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
        Class.forName("org.postgresql.Driver");

        try (Connection conn = connectWithRetry(jdbcUrl, dbUser, dbPass, 30, 2000)) {
            ensureSchema(conn);

            Map<String, City> cityMap = new HashMap<>();

            // Geocoding + upsert city
            for (String name : cities) {
                City c = geocodeCity(name);
                if (c == null) {
                    System.out.printf("No geocoding result for '%s'%n", name);
                    continue;
                }
                int cityId = upsertCity(conn, c);
                c.id = cityId;
                cityMap.put(name, c);
                System.out.printf("City '%s' -> id=%d, lat=%.4f lon=%.4f (%s)%n",
                        c.name, c.id, c.latitude, c.longitude, c.country);
            }

            // Per ogni città: fetch current + hourly, insert in city_temperature
            for (City c : cityMap.values()) {
                WeatherData wd = fetchCurrentAndHourly(c.latitude, c.longitude, pastDays, timezone);
                int inserted = insertWeather(conn, c.id, wd);
                System.out.printf("Inserted %d temperature rows for %s%n", inserted, c.name);
            }

            // Esempio SELECT: top 5 città per temperatura corrente
            runTopCurrentTemperatures(conn);

            // Esempio UPDATE: marca la misura più recente come current=true, e flagga misure orarie calde
            runUpdates(conn);
        }
    }

    /* ---------- utils and JDBC ---------- */

    static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    static Connection connectWithRetry(String url, String user, String pass, int maxAttempts, int sleepMs)
            throws InterruptedException {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", pass);
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                Connection c = DriverManager.getConnection(url, p);
                c.setAutoCommit(true);
                return c;
            } catch (SQLException e) {
                System.out.printf("Attempt %d/%d: DB not ready (%s). Retrying %dms...%n",
                        i, maxAttempts, e.getMessage(), sleepMs);
                Thread.sleep(sleepMs);
            }
        }
        throw new RuntimeException("DB connection failed");
    }

    static void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS city(
                  id SERIAL PRIMARY KEY,
                  name TEXT NOT NULL,
                  country TEXT,
                  latitude DOUBLE PRECISION NOT NULL,
                  longitude DOUBLE PRECISION NOT NULL,
                  timezone TEXT,
                  population BIGINT
                );
                CREATE TABLE IF NOT EXISTS city_temperature(
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
            """);
        }
    }

    /* ---------- HTTP calls to Open-Meteo ---------- */

    // Geocoding API: https://open-meteo.com/en/docs/geocoding-api
    static City geocodeCity(String name) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + name + "&count=1&language=en";
        System.out.println("Geocoding URL: " + url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        JSONObject json = new JSONObject(resp.body());
        if (!json.has("results")) return null;
        JSONArray results = json.getJSONArray("results");
        if (results.isEmpty()) return null;
        JSONObject r = results.getJSONObject(0);
        City c = new City();
        c.name = r.optString("name", name);
        c.country = r.optString("country", null);
        c.latitude = r.getDouble("latitude");
        c.longitude = r.getDouble("longitude");
        c.timezone = r.optString("timezone", null);
        c.population = r.optLong("population", 0);
        return c;
    }

    // Forecast API: https://open-meteo.com/en/docs
    static WeatherData fetchCurrentAndHourly(double lat, double lon, int pastDays, String timezone) throws Exception {
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current_weather=true&hourly=temperature_2m&past_days=%d&timezone=%s",
                lat, lon, pastDays, timezone);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Forecast API failed: " + resp.statusCode());
        JSONObject json = new JSONObject(resp.body());

        WeatherData wd = new WeatherData();

        // current_weather
        if (json.has("current_weather")) {
            JSONObject cw = json.getJSONObject("current_weather");
            wd.currentTime = cw.getString("time"); // ISO 8601 in tz richiesta
            wd.currentTempC = cw.getDouble("temperature");
        }

        // hourly time series
        if (json.has("hourly")) {
            JSONObject h = json.getJSONObject("hourly");
            JSONArray times = h.getJSONArray("time");
            JSONArray temps = h.getJSONArray("temperature_2m");
            wd.hourly = new ArrayList<>();
            for (int i = 0; i < times.length(); i++) {
                wd.hourly.add(new Hourly(times.getString(i), temps.isNull(i) ? null : temps.getDouble(i)));
            }
        }

        return wd;
    }

    /* ---------- DB inserts & queries ---------- */

    static int upsertCity(Connection conn, City c) throws SQLException {
        // upsert tramite unique (name,country,lat,lon) implicito: proviamo select per riuso
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT id FROM city
            WHERE name = ? AND country IS NOT DISTINCT FROM ? AND latitude = ? AND longitude = ?
        """)) {
            ps.setString(1, c.name);
            ps.setString(2, c.country);
            ps.setDouble(3, c.latitude);
            ps.setDouble(4, c.longitude);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        try (PreparedStatement ins = conn.prepareStatement("""
            INSERT INTO city(name, country, latitude, longitude, timezone, population)
            VALUES(?,?,?,?,?,?) RETURNING id
        """)) {
            ins.setString(1, c.name);
            ins.setString(2, c.country);
            ins.setDouble(3, c.latitude);
            ins.setDouble(4, c.longitude);
            ins.setString(5, c.timezone);
            if (c.population > 0) ins.setLong(6, c.population); else ins.setNull(6, Types.BIGINT);
            try (ResultSet rs = ins.executeQuery()) {
                rs.next(); return rs.getInt(1);
            }
        }
    }

    static int insertWeather(Connection conn, int cityId, WeatherData wd) throws SQLException {
        int count = 0;
        conn.setAutoCommit(false);
        try {
            // insert current
            if (wd.currentTime != null) {
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO city_temperature(city_id, ts, temperature_c, source, current)
                    VALUES (?,?,?,?,?)
                """)) {
                    ps.setInt(1, cityId);
                    ps.setTimestamp(2, Timestamp.from(Instant.parse(toUtcInstant(wd.currentTime))));
                    ps.setDouble(3, wd.currentTempC);
                    ps.setString(4, "open-meteo");
                    ps.setBoolean(5, true);
                    ps.executeUpdate();
                    count++;
                }
            }

            // insert hourly
            if (wd.hourly != null) {
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO city_temperature(city_id, ts, temperature_c, source, current)
                    VALUES (?,?,?,?,false)
                """)) {
                    for (Hourly h : wd.hourly) {
                        ps.setInt(1, cityId);
                        ps.setTimestamp(2, Timestamp.from(Instant.parse(toUtcInstant(h.time))));
                        if (h.tempC != null) ps.setDouble(3, h.tempC); else ps.setNull(3, Types.DOUBLE);
                        ps.setString(4, "open-meteo");
                        ps.addBatch();
                        count++;
                    }
                    ps.executeBatch();
                }
            }

            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
        }
        return count;
    }

    // Normalizza stringa ISO datetime → istante UTC (Open-Meteo restituisce tz locale se impostata). [1](https://open-meteo.com/en/docs)
    static String toUtcInstant(String isoLocal) {
        // Esempio "2026-01-08T10:00" in Europe/Rome → assumiamo timezone locale non fornita nella stringa
        // Per semplicità, trattiamo come OffsetDateTime/ZonedDateTime se presente, altrimenti parse come LocalDateTime in UTC.
        try {
            return OffsetDateTime.parse(isoLocal).toInstant().toString();
        } catch (Exception ignore) {
        }
        try {
            return ZonedDateTime.parse(isoLocal).toInstant().toString();
        } catch (Exception ignore) {
        }
        // Fallback: interpreta come LocalDateTime in UTC
        return LocalDateTime.parse(isoLocal).toInstant(ZoneOffset.UTC).toString();
    }

    static void runTopCurrentTemperatures(Connection conn) throws SQLException {
        System.out.println("Top 5 cities by current temperature:");
        String sql = """
            SELECT c.name, c.country, ct.temperature_c, ct.ts
            FROM city_temperature ct
            JOIN city c ON c.id = ct.city_id
            WHERE ct.current = true
            ORDER BY ct.temperature_c DESC NULLS LAST
            LIMIT 5
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.printf("  %s (%s): %.1f°C at %s%n",
                        rs.getString(1), rs.getString(2),
                        rs.getDouble(3), rs.getTimestamp(4).toInstant());
            }
        }
    }

    static void runUpdates(Connection conn) throws SQLException {
        // 1) Assicura che la più recente lettura per ciascuna città sia current=true
        String markLatestCurrent = """
            WITH latest AS (
              SELECT city_id, MAX(ts) AS max_ts
              FROM city_temperature
              GROUP BY city_id
            )
            UPDATE city_temperature ct
            SET current = (ct.ts = latest.max_ts)
            FROM latest
            WHERE ct.city_id = latest.city_id
        """;
        // 2) Flagga letture odierne >30°C come "adjusted" (esempio dimostrativo)
        String flagHot = """
            UPDATE city_temperature
            SET source = 'adjusted'
            WHERE date_trunc('day', ts AT TIME ZONE 'UTC') = date_trunc('day', now() AT TIME ZONE 'UTC')
              AND temperature_c IS NOT NULL
              AND temperature_c > 30
              AND current = false
        """;
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            int a = st.executeUpdate(markLatestCurrent);
            int b = st.executeUpdate(flagHot);
            conn.commit();
            System.out.printf("Updated 'current' markers: %d, flagged hot hourly rows: %d%n", a, b);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /* ---------- simple data holders ---------- */
    static class City {
        Integer id;
        String name;
        String country;
        double latitude, longitude;
        String timezone;
        long population;
    }

    static class Hourly {
        String time;
        Double tempC;
        Hourly(String t, Double c) { time = t; tempC = c; }
    }

    static class WeatherData {
        String currentTime;
        double currentTempC;
        List<Hourly> hourly;
    }
}
