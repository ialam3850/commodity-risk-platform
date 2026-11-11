package com.commodityrisk.ledger.ledger;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Low-level ledger writes and reads. Callers must be inside a transaction; the services that use
 * this class are {@code @Transactional}.
 *
 * <p>Locking protocol (the order matters, it's what prevents deadlocks):
 *
 * <ol>
 *   <li>Lock the book with {@link #lockBook}: {@code SELECT ... FOR UPDATE} on the book's cash
 *       account row. Everything that changes one book (fills, end-of-day) serializes here; other
 *       books are unaffected.
 *   <li>Apply balance changes in ascending (account id, asset) order. Every transaction acquires
 *       balance row locks in the same global order, so two transactions can never each hold a lock
 *       the other is waiting for.
 * </ol>
 *
 * <p>Running balances are kept only for book accounts. The house accounts (clearing, fees,
 * funding) are on the other side of every fill in every book, so a running-balance row for them
 * would be locked by every transaction in the system and cap throughput at one commit at a time
 * (measured: about 190 fills/s however many books there were). Their balances are summed from the
 * postings when asked for instead.
 */
@Repository
public class LedgerRepository {

  public static final String USD = "USD";

  private final JdbcClient jdbc;
  private volatile Set<Long> houseAccountIds;

  public LedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** House accounts are created by the migration and never change, so the ids are cached. */
  public boolean isHouseAccount(long accountId) {
    if (houseAccountIds == null) {
      houseAccountIds = Set.copyOf(jdbc.sql("select id from account where book is null").query(Long.class).list());
    }
    return houseAccountIds.contains(accountId);
  }

  /** The ids of a book's accounts, creating them on first use. */
  public record BookAccounts(long cash, long position) {}

  public record HouseAccounts(long clearingCash, long clearingPosition, long fees, long funding) {}

  public static String cashCode(String book) {
    return "BOOK:" + book + ":CASH";
  }

  public static String positionCode(String book) {
    return "BOOK:" + book + ":POSITION";
  }

  public BookAccounts ensureBook(String book) {
    insertAccountIfMissing(cashCode(book), book, "BOOK_CASH");
    insertAccountIfMissing(positionCode(book), book, "BOOK_POSITION");
    return new BookAccounts(accountId(cashCode(book)).orElseThrow(), accountId(positionCode(book)).orElseThrow());
  }

  /** Blocks until no other transaction is changing this book. Call after {@link #ensureBook}. */
  public void lockBook(String book) {
    jdbc.sql("select id from account where code = ? for update").param(cashCode(book)).query(Long.class).single();
  }

  public HouseAccounts house() {
    return new HouseAccounts(
        accountId("CLEARING:CASH").orElseThrow(),
        accountId("CLEARING:POSITION").orElseThrow(),
        accountId("FEES").orElseThrow(),
        accountId("FUNDING").orElseThrow());
  }

  public Optional<Long> accountId(String code) {
    return jdbc.sql("select id from account where code = ?").param(code).query(Long.class).optional();
  }

  public boolean bookExists(String book) {
    return accountId(cashCode(book)).isPresent();
  }

  private void insertAccountIfMissing(String code, String book, String kind) {
    jdbc.sql("insert into account (code, book, kind) values (?, ?, ?) on conflict (code) do nothing")
        .params(code, book, kind)
        .update();
  }

  /** One line of a journal entry: amount is the change in the account's holding of the asset. */
  public record Posting(long accountId, String asset, long amount) {}

  /**
   * Writes a journal entry with its postings and updates the running balances. The entry must
   * balance per asset; the database checks again at commit.
   */
  public long postEntry(String type, LocalDate businessDate, String description, Long fillId, List<Posting> postings) {
    Map<String, Long> net = new LinkedHashMap<>();
    for (Posting p : postings) net.merge(p.asset(), p.amount(), Long::sum);
    net.forEach(
        (asset, total) -> {
          if (total != 0) throw new IllegalStateException("entry does not balance: " + asset + " nets to " + total);
        });

    long entryId =
        jdbc.sql(
                """
                insert into journal_entry (entry_type, business_date, description, fill_id)
                values (?, ?, ?, ?) returning id
                """)
            .params(type, businessDate, description, fillId)
            .query(Long.class)
            .single();

    List<Posting> ordered =
        postings.stream()
            .filter(p -> p.amount() != 0)
            .sorted(Comparator.comparingLong(Posting::accountId).thenComparing(Posting::asset))
            .toList();
    for (Posting p : ordered) {
      jdbc.sql("insert into posting (entry_id, account_id, asset, amount) values (?, ?, ?, ?)")
          .params(entryId, p.accountId(), p.asset(), p.amount())
          .update();
      if (isHouseAccount(p.accountId())) continue; // no running balance: see the class comment
      // Upsert takes the row lock; the sorted order above keeps lock acquisition deadlock-free.
      jdbc.sql(
              """
              insert into balance (account_id, asset, amount) values (?, ?, ?)
              on conflict (account_id, asset) do update set amount = balance.amount + excluded.amount
              """)
          .params(p.accountId(), p.asset(), p.amount())
          .update();
    }
    return entryId;
  }

  public record Balance(String asset, long amount) {}

  public List<Balance> balances(long accountId) {
    String sql =
        isHouseAccount(accountId)
            ? "select asset, sum(amount) as amount from posting where account_id = ?"
                + " group by asset having sum(amount) <> 0 order by asset"
            : "select asset, amount from balance where account_id = ? and amount <> 0 order by asset";
    return jdbc.sql(sql).param(accountId).query(Balance.class).list();
  }

  public long balance(long accountId, String asset) {
    if (isHouseAccount(accountId)) {
      return jdbc.sql("select coalesce(sum(amount), 0) from posting where account_id = ? and asset = ?")
          .params(accountId, asset)
          .query(Long.class)
          .single();
    }
    return jdbc.sql("select amount from balance where account_id = ? and asset = ?")
        .params(accountId, asset)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  public record JournalLine(
      long entryId, String entryType, LocalDate businessDate, String description, String account, String asset, long amount) {}

  /** Every posting that touched one of the book's accounts, newest entry first. */
  public List<JournalLine> journal(String book, int limit) {
    return jdbc.sql(
            """
            select e.id as entry_id, e.entry_type, e.business_date, e.description,
                   a.code as account, p.asset, p.amount
              from journal_entry e
              join posting p on p.entry_id = e.id
              join account a on a.id = p.account_id
             where e.id in (select p2.entry_id from posting p2 join account a2 on a2.id = p2.account_id
                             where a2.book = ? order by p2.entry_id desc limit ?)
             order by e.id desc, p.id
            """)
        .params(book, limit)
        .query(JournalLine.class)
        .list();
  }
}
