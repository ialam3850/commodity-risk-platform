package com.commodityrisk.ledger.eod;

import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/books/{book}/eod")
public class EodController {

  private final EodService eod;

  public EodController(EodService eod) {
    this.eod = eod;
  }

  @Operation(
      summary = "Run end of day for a book",
      description =
          "Marks positions to the date's settlement prices and posts variation margin. Safe to"
              + " call twice: the second call returns the first result with replayed=true.")
  @PostMapping("/{date}")
  public EodService.Result run(
      @PathVariable String book, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return eod.run(book, date);
  }
}
