package com.commodityrisk.ledger.fill;

import com.commodityrisk.ledger.instrument.Instrument;
import com.commodityrisk.ledger.instrument.InstrumentRepository;
import com.commodityrisk.ledger.ledger.LedgerRepository;
import com.commodityrisk.ledger.ledger.LedgerRepository.Posting;
import com.commodityrisk.ledger.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records fills in the ledger exactly once per Idempotency-Key.
 *
 * <p>A futures fill moves no cash: buying a contract costs nothing up front (margin is collateral,
 * not payment). It moves contracts - the book gains them, the clearing house is the other side -
 * and the fee moves cash. The price matters later, when end-of-day marks the new position to the
 * settlement price (see {@code EodService}).
 */
@Service
public class FillService {

  /** The outcome of a POST: the response and whether it was replayed from an earlier request. */
  public record Result(int status, FillResponse body, boolean replayed) {}

  private final JdbcClient jdbc;
  private final InstrumentRepository instruments;
  private final LedgerRepository ledger;
  private final IdempotencyRepository idempotency;
  private final ObjectMapper json;

  public FillService(
      JdbcClient jdbc,
      InstrumentRepository instruments,
      LedgerRepository ledger,
      IdempotencyRepository idempotency,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.instruments = instruments;
    this.ledger = ledger;
    this.idempotency = idempotency;
    this.json = json;
  }

  @Transactional
  public Result record(String key, FillRequest req) {
    if (key == null || key.isBlank() || key.length() > 200) {
      throw ApiException.badRequest("Idempotency-Key header must be 1-200 characters");
    }
    String hash = hash(req);
    if (!idempotency.claim(key, hash)) {
      IdempotencyRepository.Stored stored = idempotency.find(key).orElseThrow();
      if (!stored.requestHash().equals(hash)) {
        throw ApiException.unprocessable("Idempotency-Key " + key + " was already used for a different request");
      }
      return new Result(stored.responseCode(), read(stored.responseBody()), true);
    }

    String symbol = req.symbol().trim().toUpperCase();
    Instrument inst =
        instruments.find(symbol).orElseThrow(() -> ApiException.unprocessable("unknown instrument " + symbol));
    if (!inst.onTick(req.price())) {
      throw ApiException.badRequest("price " + req.price() + " is not a multiple of the tick size " + inst.tickSize());
    }
    if (req.tradeDate().isAfter(inst.lastTradeDate())) {
      throw ApiException.unprocessable(symbol + " stopped trading on " + inst.lastTradeDate());
    }

    LedgerRepository.BookAccounts book = ledger.ensureBook(req.book());
    ledger.lockBook(req.book());
    Boolean settled =
        jdbc.sql("select exists (select 1 from eod_run where book = ? and business_date >= ?)")
            .params(req.book(), req.tradeDate())
            .query(Boolean.class)
            .single();
    if (settled) {
      throw ApiException.conflict(
          "book " + req.book() + " is already settled through " + req.tradeDate() + "; book the fill on a later date");
    }

    Instant executedAt = req.executedAt() != null ? req.executedAt() : Instant.now().truncatedTo(ChronoUnit.MICROS);
    long fillId =
        jdbc.sql(
                """
                insert into fill (book, symbol, side, quantity, price, fee_cents, trade_date, executed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                """)
            .params(
                req.book(),
                symbol,
                req.side().name(),
                req.quantity(),
                req.price(),
                req.feeCents(),
                req.tradeDate(),
                java.sql.Timestamp.from(executedAt))
            .query(Long.class)
            .single();

    LedgerRepository.HouseAccounts house = ledger.house();
    long qty = req.signedQuantity();
    List<Posting> postings = new ArrayList<>();
    postings.add(new Posting(book.position(), symbol, qty));
    postings.add(new Posting(house.clearingPosition(), symbol, -qty));
    if (req.feeCents() > 0) {
      postings.add(new Posting(book.cash(), LedgerRepository.USD, -req.feeCents()));
      postings.add(new Posting(house.fees(), LedgerRepository.USD, req.feeCents()));
    }
    String description =
        "%s %s %d %s @ %s".formatted(req.book(), req.side(), req.quantity(), symbol, req.price().toPlainString());
    long entryId = ledger.postEntry("FILL", req.tradeDate(), description, fillId, postings);

    FillResponse body =
        new FillResponse(
            fillId,
            entryId,
            req.book(),
            symbol,
            req.side().name(),
            req.quantity(),
            req.price(),
            req.feeCents(),
            req.tradeDate(),
            executedAt,
            ledger.balance(book.position(), symbol));
    idempotency.complete(key, 201, write(body));
    return new Result(201, body, false);
  }

  /**
   * Fingerprint of the request's meaning, so "3.5" and "3.500" or "ngf27" and "NGF27" count as the
   * same request.
   */
  static String hash(FillRequest r) {
    String canonical =
        String.join(
            "|",
            r.book(),
            r.symbol().trim().toUpperCase(),
            r.side().name(),
            Integer.toString(r.quantity()),
            r.price().stripTrailingZeros().toPlainString(),
            Long.toString(r.feeCents()),
            r.tradeDate().toString(),
            String.valueOf(r.executedAt()));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String write(FillResponse r) {
    try {
      return json.writeValueAsString(r);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private FillResponse read(String body) {
    try {
      return json.readValue(body, FillResponse.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
