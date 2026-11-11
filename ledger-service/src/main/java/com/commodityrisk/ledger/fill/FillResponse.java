package com.commodityrisk.ledger.fill;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** What POST /api/fills returns: the stored fill and the journal entry that recorded it. */
public record FillResponse(
    long fillId,
    long journalEntryId,
    String book,
    String symbol,
    String side,
    int quantity,
    BigDecimal price,
    long feeCents,
    LocalDate tradeDate,
    Instant executedAt,
    long positionAfter) {}
