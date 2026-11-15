package com.commodityrisk.ledger.market;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketDataController.class)
@Import(MarketDataRequestTest.Config.class)
class MarketDataRequestTest {
  @TestConfiguration
  static class Config {
    @Bean
    JdbcClient jdbcClient() {
      return JdbcClient.create(new AbstractDataSource() {
        @Override public Connection getConnection() throws SQLException {
          throw new SQLException("validation must run before database access");
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
          return getConnection();
        }
      });
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void nullRowsAreRejectedBeforeDatabaseAccess() throws Exception {
    for (String route : List.of("/api/settlements", "/api/factor-returns")) {
      mvc.perform(post(route).contentType("application/json").content("[null]"))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void aMissingReturnIsRejectedBeforeDatabaseAccess() throws Exception {
    mvc.perform(post("/api/factor-returns").contentType("application/json")
        .content("[{\"factor\":\"NG:C1\",\"date\":\"2026-10-20\"}]"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void emptyUploadsDoNotNeedADatabase() throws Exception {
    for (String route : List.of("/api/settlements", "/api/factor-returns")) {
      mvc.perform(post(route).contentType("application/json").content("[]"))
          .andExpect(status().isOk());
    }
  }
}
