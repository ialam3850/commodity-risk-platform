package com.commodityrisk.ledger.web;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every error leaves as an RFC 9457 problem document ({"status", "title", "detail"}) whose detail
 * says what to fix, e.g. "quantity must be greater than or equal to 1".
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE) // ahead of Spring's generic handlers
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ProblemDetail api(ApiException e) {
    return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
  }

  /** @Valid on a request body object. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail invalidBody(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  /** Constraints on method parameters, e.g. each element of a List body. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  ProblemDetail invalidParameters(HandlerMethodValidationException e) {
    String detail =
        e.getAllErrors().stream()
            .map(err -> (err instanceof FieldError f ? f.getField() + " " : "") + err.getDefaultMessage())
            .distinct()
            .sorted()
            .limit(10)
            .collect(Collectors.joining("; "));
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail unreadable(HttpMessageNotReadableException e) {
    String cause = e.getMostSpecificCause().getMessage();
    String firstLine = cause == null ? "unreadable body" : cause.split("\n")[0];
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "malformed request body: " + firstLine.substring(0, Math.min(firstLine.length(), 200)));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ProblemDetail missingHeader(MissingRequestHeaderException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "missing required header " + e.getHeaderName());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ProblemDetail missingParam(MissingServletRequestParameterException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "missing query parameter " + e.getParameterName());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "invalid value '" + e.getValue() + "' for " + e.getName());
  }

  @ExceptionHandler(ArithmeticException.class)
  ProblemDetail arithmetic(ArithmeticException e) {
    log.error("non-exact money arithmetic", e);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY, "amount is not a whole number of cents: " + e.getMessage());
  }
}
