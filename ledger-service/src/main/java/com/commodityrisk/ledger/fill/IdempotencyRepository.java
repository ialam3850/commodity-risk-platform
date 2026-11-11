package com.commodityrisk.ledger.fill;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores the response to each Idempotency-Key so a retried request gets the same answer instead of
 * a second fill.
 *
 * <p>The race this handles: two copies of the same request arrive at once (a client timed out and
 * retried while the first was still running). Both run {@link #claim}. Postgres lets one INSERT
 * through; the other blocks on the primary key until the first transaction commits, then sees the
 * conflict, inserts nothing, and reads the stored response. If the first transaction rolls back
 * instead, the second's INSERT succeeds and it does the work itself.
 */
@Repository
public class IdempotencyRepository {

  private final JdbcClient jdbc;

  public IdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** True if this transaction now owns the key; false if someone else already used it. */
  public boolean claim(String key, String requestHash) {
    return jdbc.sql("insert into idempotency_key (key, request_hash) values (?, ?) on conflict (key) do nothing")
            .params(key, requestHash)
            .update()
        == 1;
  }

  public record Stored(String requestHash, Integer responseCode, String responseBody) {}

  public Optional<Stored> find(String key) {
    return jdbc.sql("select request_hash, response_code, response_body from idempotency_key where key = ?")
        .param(key)
        .query(Stored.class)
        .optional();
  }

  public void complete(String key, int code, String body) {
    jdbc.sql("update idempotency_key set response_code = ?, response_body = ? where key = ?")
        .params(code, body, key)
        .update();
  }
}
