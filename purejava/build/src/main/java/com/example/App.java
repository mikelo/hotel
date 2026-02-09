// add tracer to this code
package com.example;

import org.json.JSONObject;
import org.json.JSONArray;

// import com.instana.sdk.annotation.Span;
// import com.instana.sdk.support.SpanSupport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.ZoneId;

// ==== OpenTelemetry imports ====
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
// Per HTTP/4318 (alternativa):
// import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

public class App {

    // ===== Tracer OTel riutilizzabile =====
    private static final Tracer tracer = Otel.init();

    public static void main(String[] args) throws Exception {
        Span startup = tracer.spanBuilder("app.startup")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope s = startup.makeCurrent()) {

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

            try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass)) {

                // ensureSchema tracciato come DB span
                ensureSchema(conn);

                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    scheduler.shutdown();
                    try { scheduler.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                }));

                scheduler.scheduleAtFixedRate(new Runnable() {
                    public void run() {
                        // span del ciclo di polling
                        Span poll = tracer.spanBuilder("poll.cycle")
                                .setSpanKind(SpanKind.INTERNAL)
                                .setAttribute("poll.interval.seconds", pollSeconds)
                                .startSpan();
                        try (Scope ps = poll.makeCurrent()) {
                            for (String cityRaw : cities) {
                                String city = cityRaw.trim();
                                Span spanCity = tracer.spanBuilder("poll.city")
                                        .setSpanKind(SpanKind.INTERNAL)
                                        .setAttribute("city.name", city)
                                        .startSpan();
                                try (Scope cs = spanCity.makeCurrent()) {
                                    JSONObject current = fetchCurrentWeather(city);
                                    if (current != null) {
                                        insertCurrent(conn, city, current);
                                    }
                                } catch (Exception e) {
                                    spanCity.recordException(e);
                                    spanCity.setStatus(StatusCode.ERROR, e.getMessage());
                                } finally {
                                    spanCity.end();
                                }
                            }
                            System.out.println("Update done at " + Instant.now());
                        } catch (Exception e) {
                            poll.recordException(e);
                            poll.setStatus(StatusCode.ERROR, e.getMessage());
                        } finally {
                            poll.end();
                        }
                    }
                }, 0, pollSeconds, TimeUnit.SECONDS);
            }

        } catch (Exception e) {
            startup.recordException(e);
            startup.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            startup.end();
        }
    }

    private static String getEnv(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private static JSONObject fetchCurrentWeather(String city) throws Exception {
        Span span = tracer.spanBuilder("fetchCurrentWeather")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("city.name", city)
                .startSpan();
        try (Scope s = span.makeCurrent()) {
            String encodedCity = java.net.URLEncoder.encode(city, "UTF-8");

            // Geocoding
            String urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" + encodedCity + "&count=1";
            String geoStr = httpGet(urlStr);
            JSONObject geo = new JSONObject(geoStr);
            if (!geo.has("results")) return null;
            JSONObject r = geo.getJSONArray("results").getJSONObject(0);
            double lat = r.getDouble("latitude");
            double lon = r.getDouble("longitude");
            String timezone = r.getString("timezone");

            // Forecast current
            String forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true";
            String forecastStr = httpGet(forecastUrl);
            JSONObject forecast = new JSONObject(forecastStr);
            forecast.put("timezone", timezone);
            return forecast;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        // client span per chiamata HTTP
        Span span = tracer.spanBuilder("http.get")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.method", "GET")
                .setAttribute("http.url", urlStr)
                .startSpan();
        HttpURLConnection conn = null;
        try (Scope s = span.makeCurrent()) {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            span.setAttribute("http.status_code", code);

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream()
            ));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line);
            }
            in.close();

            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code + " for " + urlStr);
            }
            return sb.toString();

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
            span.end();
        }
    }

    private static void ensureSchema(Connection conn) throws SQLException {
        Span span = tracer.spanBuilder("db.ensureSchema")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "postgresql")
                .startSpan();
        try (Scope s = span.makeCurrent(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS city_temperature (" +
                       "id SERIAL PRIMARY KEY, " +
                       "city TEXT, " +
                       "ts TIMESTAMPTZ, " +
                       "temperature_c DOUBLE PRECISION)");
        } catch (SQLException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private static void insertCurrent(Connection conn, String city, JSONObject current) throws SQLException {
        Span span = tracer.spanBuilder("db.insertCurrent")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "postgresql")
                .setAttribute("db.operation", "INSERT")
                .setAttribute("db.statement", "INSERT INTO city_temperature(city, ts, temperature_c) VALUES (?, ?, ?)")
                .setAttribute("city.name", city)
                .startSpan();

        PreparedStatement ps = null;
        try (Scope s = span.makeCurrent()) {
            ps = conn.prepareStatement("INSERT INTO city_temperature(city, ts, temperature_c) VALUES (?, ?, ?)");
            ps.setString(1, city);

            JSONObject cw = current.getJSONObject("current_weather");
            Instant ts = parseOpenMeteoTime(cw.getString("time"), current.getString("timezone"));
            ps.setTimestamp(2, Timestamp.from(ts));
            ps.setDouble(3, cw.getDouble("temperature"));

            ps.executeUpdate();
        } catch (SQLException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            if (ps != null) try { ps.close(); } catch (Exception ignore) {}
            span.end();
        }
    }

    private static Instant parseOpenMeteoTime(String isoLocal, String timezone) {
        // es: 2026-01-16T15:00 (NO offset) → LocalDateTime + ZoneId (Java 8 safe)
        LocalDateTime ldt = LocalDateTime.parse(isoLocal);
        ZoneId zone = ZoneId.of(timezone); // es Europe/Rome
        return ldt.atZone(zone).toInstant();
    }

    // ===== Helper OTel: TraceProvider + Exporter OTLP =====
    static final class Otel {
        private static volatile Tracer tracer;

        static Tracer init() {
            if (tracer != null) return tracer;

            synchronized (Otel.class) {
                if (tracer != null) return tracer;

                String serviceName = env("OTEL_SERVICE_NAME", "miademo");
                String endpoint = env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://instana-agent:4317");
                double ratio = parseDouble(env("OTEL_TRACES_SAMPLER_ARG", "1.0"), 1.0);

                // Exporter OTLP gRPC (4317)
                OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                        .setEndpoint(endpoint)
                        .build();

                // Se vuoi usare HTTP/4318:
                // OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                //        .setEndpoint(endpoint.endsWith("/v1/traces") ? endpoint : (endpoint + "/v1/traces"))
                //        .build();

                Resource resource = Resource.getDefault().merge(
                        Resource.create(
                                Attributes.of(
                                        AttributeKey.stringKey("service.name"), serviceName,
                                        AttributeKey.stringKey("service.version"), env("SERVICE_VERSION", "1.0.0"),
                                        AttributeKey.stringKey("deployment.environment"), env("ENV", "dev")
                                )
                        )
                );

                SdkTracerProvider provider = SdkTracerProvider.builder()
                        .setResource(resource)
                        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(ratio)))
                        .addSpanProcessor(
                                BatchSpanProcessor.builder(exporter)
                                        .build()
                        )
                        .build();

                OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                        .setTracerProvider(provider)
                        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                        .build();

                // chiusura ordinata
                Runtime.getRuntime().addShutdownHook(new Thread(provider::close));

                tracer = openTelemetry.getTracer("app-tracer");
                return tracer;
            }
        }

        private static String env(String k, String d) {
            String v = System.getenv(k);
            return (v == null || v.trim().isEmpty()) ? d : v.trim();
        }

        private static double parseDouble(String s, double def) {
            try { return Double.parseDouble(s); } catch (Exception e) { return def; }
        }
    }
}