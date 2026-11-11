package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 1,000 fills from 32 threads at once, across 4 books and 3 contracts, with 200 of them also sent
 * a second time with the same Idempotency-Key (a client retrying while the first attempt is still
 * in flight). Afterwards the ledger must be exactly right.
 */
class ConcurrencyIT extends IntegrationTest {

  record Sent(String key, Map<String, Object> body) {}

  @Test
  void thousandConcurrentFillsWithRetriesLeaveTheLedgerExact() throws Exception {
    List<String> symbols = List.of("NGF27", "NGG27", "CLF27");
    symbols.forEach(this::instrument);
    List<String> books = List.of("ALPHA", "BRAVO", "CHARLIE", "DELTA");

    Random rng = new Random(7);
    List<Sent> unique = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      String symbol = symbols.get(rng.nextInt(symbols.size()));
      String price = symbol.startsWith("CL") ? String.format("%.2f", 60 + rng.nextInt(1000) / 100.0)
                                             : String.format("%.3f", 3 + rng.nextInt(1000) / 1000.0);
      unique.add(new Sent(newKey(), fill(books.get(rng.nextInt(books.size())), symbol, rng.nextBoolean() ? "BUY" : "SELL",
          1 + rng.nextInt(20), price, 100 + rng.nextInt(500), "2026-10-20")));
    }
    List<Sent> all = new ArrayList<>(unique);
    all.addAll(unique.subList(0, 200)); // retries
    Collections.shuffle(all, rng);

    ExecutorService pool = Executors.newFixedThreadPool(32);
    AtomicInteger replayed = new AtomicInteger();
    Map<String, Long> fillIdByKey = new ConcurrentHashMap<>();
    List<Future<?>> futures = new ArrayList<>();
    for (Sent s : all) {
      futures.add(pool.submit(() -> {
        ResponseEntity<Map> r = postFill(s.key(), s.body(), Map.class);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        if ("true".equals(r.getHeaders().getFirst("Idempotent-Replayed"))) replayed.incrementAndGet();
        long id = ((Number) r.getBody().get("fillId")).longValue();
        Long previous = fillIdByKey.putIfAbsent(s.key(), id);
        if (previous != null) assertThat(id).isEqualTo(previous); // a retry sees the same fill
      }));
    }
    for (Future<?> f : futures) f.get(); // rethrows any assertion failure from a worker
    pool.shutdown();
    assertThat(pool.awaitTermination(1, TimeUnit.MINUTES)).isTrue();

    // Exactly one fill per key, and the retries were all answered from the stored response.
    assertThat(jdbc.sql("select count(*) from fill").query(Long.class).single()).isEqualTo(1000);
    assertThat(replayed.get()).isEqualTo(200);

    // Every asset nets to zero across all accounts (double entry holds globally) ...
    assertThat(jdbc.sql("select count(*) from (select asset from posting group by asset having sum(amount) <> 0) x")
        .query(Long.class).single()).isZero();
    // ... every running balance equals the sum of its postings (no lost updates) ...
    assertThat(jdbc.sql("""
        select count(*) from balance b
         where b.amount <> (select coalesce(sum(p.amount), 0) from posting p
                             where p.account_id = b.account_id and p.asset = b.asset)
        """).query(Long.class).single()).isZero();
    // ... and each book's position equals the signed sum of its fills, computed independently here.
    Map<String, Long> expected = new java.util.TreeMap<>();
    for (Sent s : unique) {
      Map<String, Object> b = s.body();
      long q = ((Number) b.get("quantity")).longValue() * ("BUY".equals(b.get("side")) ? 1L : -1L);
      expected.merge(b.get("book") + "/" + b.get("symbol"), q, Long::sum);
    }
    for (var e : expected.entrySet()) {
      String[] bs = e.getKey().split("/");
      Long actual = jdbc.sql("""
          select coalesce(sum(b.amount), 0) from balance b join account a on a.id = b.account_id
           where a.code = ? and b.asset = ?
          """).params("BOOK:" + bs[0] + ":POSITION", bs[1]).query(Long.class).single();
      assertThat(actual).as(e.getKey()).isEqualTo(e.getValue());
    }
    long fees = unique.stream().mapToLong(s -> ((Number) s.body().get("feeCents")).longValue()).sum();
    assertThat(jdbc.sql("select sum(amount) from posting p join account a on a.id = p.account_id where a.code = 'FEES'")
        .query(Long.class).single()).isEqualTo(fees);
  }
}
