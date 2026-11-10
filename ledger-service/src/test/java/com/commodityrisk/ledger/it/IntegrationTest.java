package com.commodityrisk.ledger.it;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that run the whole service over HTTP against a real PostgreSQL.
 *
 * <p>By default a throwaway PostgreSQL 16 starts in Docker (Testcontainers). If Docker isn't
 * available, point the tests at any empty database instead:
 *
 * <pre>
 *   TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/ledger_test TEST_DATABASE_USER=postgres ./mvnw verify
 * </pre>
 *
 * Tables are emptied before each test, so tests don't depend on each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

  private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");
  private static PostgreSQLContainer<?> postgres;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    if (EXTERNAL_URL != null) {
      registry.add("spring.datasource.url", () -> EXTERNAL_URL);
      registry.add("spring.datasource.username", () -> env("TEST_DATABASE_USER", "postgres"));
      registry.add("spring.datasource.password", () -> env("TEST_DATABASE_PASSWORD", ""));
    } else {
      if (postgres == null) {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start(); // stopped by Testcontainers' Ryuk when the JVM exits
      }
      registry.add("spring.datasource.url", postgres::getJdbcUrl);
      registry.add("spring.datasource.username", postgres::getUsername);
      registry.add("spring.datasource.password", postgres::getPassword);
    }
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return v != null ? v : fallback;
  }

  @Autowired protected TestRestTemplate http;
  @Autowired protected JdbcClient jdbc;

  @BeforeEach
  void emptyTables() {
    // TRUNCATE doesn't fire the append-only row triggers, which is what we want here.
    jdbc.sql(
            """
            truncate posting, journal_entry, fill, balance, idempotency_key, settlement_price,
                     factor_return, eod_run, instrument restart identity cascade
            """)
        .update();
    jdbc.sql("delete from account where book is not null").update();
  }

  protected void instrument(String symbol) {
    http.postForEntity("/api/instruments", java.util.Map.of("symbol", symbol), String.class);
  }

  protected void settle(String symbol, String date, String price) {
    ResponseEntity<String> r =
        http.postForEntity(
            "/api/settlements",
            java.util.List.of(java.util.Map.of("symbol", symbol, "date", date, "price", price)),
            String.class);
    if (!r.getStatusCode().is2xxSuccessful()) throw new AssertionError(r.getBody());
  }

  protected <T> ResponseEntity<T> postFill(String key, Object body, Class<T> type) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) h.set("Idempotency-Key", key);
    return http.postForEntity("/api/fills", new HttpEntity<>(body, h), type);
  }

  protected static java.util.Map<String, Object> fill(
      String book, String symbol, String side, int qty, String price, long feeCents, String tradeDate) {
    return java.util.Map.of(
        "book", book, "symbol", symbol, "side", side, "quantity", qty, "price", price, "feeCents", feeCents,
        "tradeDate", tradeDate);
  }

  protected static String newKey() {
    return UUID.randomUUID().toString();
  }
}
