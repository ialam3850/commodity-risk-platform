package com.commodityrisk.ledger.instrument;

import com.commodityrisk.ledger.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {

  private final InstrumentRepository instruments;

  public InstrumentController(InstrumentRepository instruments) {
    this.instruments = instruments;
  }

  public record CreateInstrument(@NotBlank String symbol) {}

  @Operation(
      summary = "Add a futures contract by symbol",
      description =
          "Terms (multiplier, tick size, last trading day) come from the product rules, e.g. NGF27 ="
              + " NG Jan 2027. Creating an existing symbol returns it unchanged (200).")
  @PostMapping
  public ResponseEntity<Instrument> create(@Valid @RequestBody CreateInstrument req) {
    String symbol = req.symbol().trim().toUpperCase();
    ContractSpecs.ParsedSymbol parsed =
        ContractSpecs.parse(symbol)
            .orElseThrow(() -> ApiException.badRequest("symbol must look like NGF27 (root, month code, year)"));
    ContractSpecs.Spec spec =
        ContractSpecs.spec(parsed.root())
            .orElseThrow(() -> ApiException.badRequest("unknown product root " + parsed.root()));
    Instrument i =
        new Instrument(
            symbol,
            parsed.root(),
            parsed.month().atDay(1),
            spec.multiplier(),
            spec.tickSize(),
            ContractSpecs.lastTradeDate(parsed.root(), parsed.month()));
    boolean created = instruments.insert(i);
    return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(instruments.find(symbol).orElseThrow());
  }

  @GetMapping
  public List<Instrument> list() {
    return instruments.findAll();
  }

  @GetMapping("/{symbol}")
  public Instrument get(@PathVariable String symbol) {
    return instruments.find(symbol).orElseThrow(() -> ApiException.notFound("no instrument " + symbol));
  }
}
