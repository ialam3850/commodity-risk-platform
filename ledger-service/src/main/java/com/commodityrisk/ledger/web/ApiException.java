package com.commodityrisk.ledger.web;

import org.springframework.http.HttpStatus;

/** An error that maps directly to an HTTP status and a message the client can act on. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  public static ApiException unprocessable(String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }
}
