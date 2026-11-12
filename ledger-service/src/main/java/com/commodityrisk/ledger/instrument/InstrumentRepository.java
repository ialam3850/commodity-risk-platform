package com.commodityrisk.ledger.instrument;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InstrumentRepository {

  private final JdbcClient jdbc;

  public InstrumentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Instrument> find(String symbol) {
    return jdbc.sql("select * from instrument where symbol = ?")
        .param(symbol)
        .query(Instrument.class)
        .optional();
  }

  public List<Instrument> findAll() {
    return jdbc.sql("select * from instrument order by root, contract_month").query(Instrument.class).list();
  }

  /** Inserts the instrument; returns false if the symbol already existed. */
  public boolean insert(Instrument i) {
    return jdbc.sql(
                """
                insert into instrument (symbol, root, contract_month, multiplier, tick_size, last_trade_date)
                values (?, ?, ?, ?, ?, ?) on conflict (symbol) do nothing
                """)
            .params(i.symbol(), i.root(), i.contractMonth(), i.multiplier(), i.tickSize(), i.lastTradeDate())
            .update()
        == 1;
  }
}
