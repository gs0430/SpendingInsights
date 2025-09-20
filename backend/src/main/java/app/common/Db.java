package app.common;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny JDBC helper for Neon/Postgres.
 * Env vars expected:
 *   jdbc_url      = jdbc:postgresql://<host>/<db>?sslmode=require
 *   db_user     = <username>
 *   db_pass = <password>
 */
public final class Db {

  private Db() { /* no instances */ }

  /** Open a new connection using env vars. Caller closes (use try-with-resources). */
  public static Connection connect() throws SQLException {
    String url  = System.getenv("jdbc_url");
    String user = System.getenv("db_user");
    String pass = System.getenv("db_pass");
    if (url == null || user == null || pass == null) {
      throw new SQLException("DB env vars missing: jdbc_url / db_user / db_pass");
    }
    return DriverManager.getConnection(url, user, pass);
  }

  /** Functional interface to map a ResultSet row to a value. */
  @FunctionalInterface
  public interface RowMapper<T> { T map(ResultSet rs) throws Exception; }

  /** Execute a SELECT that returns at most one row; map it or return null. */
  public static <T> T one(Connection c, String sql, RowMapper<T> mapper, Object... params) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapper.map(rs) : null;
      }
    }
  }

  /** Execute a SELECT that returns many rows; map each. */
  public static <T> List<T> many(Connection c, String sql, RowMapper<T> mapper, Object... params) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> out = new ArrayList<>();
        while (rs.next()) out.add(mapper.map(rs));
        return out;
      }
    }
  }

  /** Execute an INSERT/UPDATE/DELETE; returns affected row count. */
  public static int exec(Connection c, String sql, Object... params) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      return ps.executeUpdate();
    }
  }

  /** Run work in an existing connection within a transaction. */
  public static <T> T inTx(Connection c, TxWork<T> work) throws Exception {
    boolean old = c.getAutoCommit();
    c.setAutoCommit(false);
    try {
      T result = work.run();
      c.commit();
      return result;
    } catch (Exception e) {
      try { c.rollback(); } catch (Exception ignore) {}
      throw e;
    } finally {
      try { c.setAutoCommit(old); } catch (Exception ignore) {}
    }
  }

  @FunctionalInterface
  public interface TxWork<T> { T run() throws Exception; }

  // ---------- Convenience helpers your handlers were calling ----------

  /** Work that needs a Connection and a transaction, returns a value. */
  @FunctionalInterface
  public interface ConnTxFn<T> { T run(Connection c) throws Exception; }

  /** Work that needs a Connection and a transaction, returns nothing. */
  @FunctionalInterface
  public interface ConnTxVoidFn { void run(Connection c) throws Exception; }

  /** Open a connection, start a transaction, run work, commit/rollback, close. */
  public static <T> T withTx(ConnTxFn<T> work) throws Exception {
    try (Connection c = connect()) {
      return inTx(c, () -> work.run(c));
    }
  }

  /** Void overload of withTx. */
  public static void withTx(ConnTxVoidFn work) throws Exception {
    try (Connection c = connect()) {
      inTx(c, () -> { work.run(c); return null; });
    }
  }

  /** Work that needs a Connection but no explicit transaction (auto-commit OK). */
  @FunctionalInterface
  public interface ConnFn<T> { T run(Connection c) throws Exception; }
  @FunctionalInterface
  public interface ConnVoidFn { void run(Connection c) throws Exception; }

  /** Open a connection, run work (no explicit tx), close. */
  public static <T> T withConn(ConnFn<T> work) throws Exception {
    try (Connection c = connect()) {
      return work.run(c);
    }
  }

  public static void withConn(ConnVoidFn work) throws Exception {
    try (Connection c = connect()) {
      work.run(c);
    }
  }

  // ---------- Internal param binding ----------

  /** Bind parameters to a PreparedStatement with sensible defaults. */
  private static void bind(PreparedStatement ps, Object... params) throws SQLException {
    if (params == null) return;
    for (int i = 0; i < params.length; i++) {
      Object v = params[i];
      if (v instanceof java.time.LocalDate ld) {
        ps.setObject(i + 1, ld); // PostgreSQL date
      } else if (v instanceof java.util.UUID uuid) {
        ps.setObject(i + 1, uuid); // PostgreSQL uuid
      } else {
        ps.setObject(i + 1, v);
      }
    }
  }
}