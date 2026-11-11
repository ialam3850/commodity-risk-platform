package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/** The database itself refuses to hold an unbalanced or edited ledger, whatever the application does. */
class LedgerInvariantsIT extends IntegrationTest {

  @Autowired TransactionTemplate tx;

  @Test
  void unbalancedEntryIsRejectedAtCommit() {
    http.postForEntity("/api/books/MACRO/deposits", Map.of("amountCents", 1_000_00, "date", "2026-10-20"), String.class);
    long account = jdbc.sql("select id from account where code = 'BOOK:MACRO:CASH'").query(Long.class).single();
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
          long entry = jdbc.sql("insert into journal_entry (entry_type, business_date, description) values ('DEPOSIT', '2026-10-20', 'free money') returning id")
              .query(Long.class).single();
          jdbc.sql("insert into posting (entry_id, account_id, asset, amount) values (?, ?, 'USD', 500)")
              .params(entry, account).update();
        }))
        // The deferred trigger fires at COMMIT, so the failure surfaces as a failed commit.
        .isInstanceOf(TransactionSystemException.class)
        .rootCause()
        .hasMessageContaining("does not balance");
    assertThat(jdbc.sql("select count(*) from journal_entry").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void postingsCannotBeEditedOrDeleted() {
    http.postForEntity("/api/books/MACRO/deposits", Map.of("amountCents", 1_000_00, "date", "2026-10-20"), String.class);
    assertThatThrownBy(() -> jdbc.sql("update posting set amount = amount * 2").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("delete from journal_entry").update()).hasMessageContaining("append-only");
  }

  @Test
  void depositCreatesTheBookAndBalances() {
    Map<?, ?> book = http.postForObject("/api/books/NEWBOOK/deposits", Map.of("amountCents", 2_500_000, "date", "2026-10-20"), Map.class);
    assertThat(((Number) book.get("cashCents")).longValue()).isEqualTo(2_500_000);
    assertThat(jdbc.sql("select sum(amount) from posting where asset = 'USD'").query(Long.class).single()).isZero();
  }
}
