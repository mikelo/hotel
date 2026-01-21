
package com.example;

import org.json.JSONObject;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.ZoneId;


public class App {

    public static void main(String[] args) throws Exception {
        String dbHost = getEnv("DB_HOST", "localhost");
        String dbPort = getEnv("DB_PORT", "5432");
        String dbName = getEnv("DB_NAME", "demo");
        String dbUser = getEnv("DB_USER", "demo");
        String dbPass = getEnv("DB_PASSWORD", "demo");

        String citiesCsv = getEnv("CITIES", "Milan,Paris,Berlin");
        List<String> cities = Arrays.asList(citiesCsv.split(","));
        int pollSeconds = Integer.parseInt(getEnv("POLL_SECONDS", "5"));

        String jdbcUrl = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
        Class.forName("org.postgresql.Driver");

        Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
        ensureSchema(conn);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                try {
                    for (String city : cities) {
                        JSONObject current = fetchCurrentWeather(city.trim());
                        if (current != null) {
                            insertCurrent(conn, city.trim(), current);
                        }
                    }
                    System.out.println("Update done at " + Instant.now());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, pollSeconds, TimeUnit.SECONDS);
    }

    private static String getEnv(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private static JSONObject fetchCurrentWeather(String city) throws Exception {
        String encodedCity = java.net.URLEncoder.encode(city, "UTF-8");
        String urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" + encodedCity + "&count=1";
        JSONObject geo = new JSONObject(httpGet(urlStr));
        if (!geo.has("results")) return null;
        JSONObject r = geo.getJSONArray("results").getJSONObject(0);
        double lat = r.getDouble("latitude");
        double lon = r.getDouble("longitude");

        String timezone = r.getString("timezone");


        String forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true";
        JSONObject forecast = new JSONObject(httpGet(forecastUrl));
        forecast.put("timezone", timezone);
        return forecast;
        // return forecast.has("current_weather") ? forecast.getJSONObject("current_weather") : null;
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line);
        }
        in.close();
        return sb.toString();
    }

    private static void ensureSchema(Connection conn) throws SQLException {
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE IF NOT EXISTS city_temperature (id SERIAL PRIMARY KEY, city TEXT, ts TIMESTAMPTZ, temperature_c DOUBLE PRECISION)");
        st.close();
    }

    private static void insertCurrent(Connection conn, String city, JSONObject current) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("INSERT INTO city_temperature(city, ts, temperature_c) VALUES (?, ?, ?)");
        ps.setString(1, city);
        JSONObject forecast = current.getJSONObject("current_weather");
        // JSONObject forecast = forecast.has("current_weather") ? forecast.getJSONObject("current_weather") : null;
        ps.setTimestamp(2, Timestamp.from(parseOpenMeteoTime(forecast.getString("time"), current.getString("timezone"))));
        ps.setDouble(3, forecast.getDouble("temperature"));
        ps.executeUpdate();
        ps.close();
    }

    private static Instant parseOpenMeteoTime(String isoLocal, String timezone) {
        // es: 2026-01-16T15:00
        LocalDateTime ldt = LocalDateTime.parse(isoLocal);
        ZoneId zone = ZoneId.of(timezone); // es Europe/Rome
        return ldt.atZone(zone).toInstant();
    }

}
