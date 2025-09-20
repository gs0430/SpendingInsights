package app;

import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import java.time.LocalDate;
import app.common.Db; // uses your DB helper

public class ListTransactionsHandler implements RequestHandler<Map<String,Object>, APIGatewayProxyResponseEvent> {
  private static final ObjectMapper M = new ObjectMapper();

  @Override
  public APIGatewayProxyResponseEvent handleRequest(Map<String,Object> in, Context ctx) {
    @SuppressWarnings("unchecked")
    Map<String,String> q = (Map<String,String>) in.getOrDefault("queryStringParameters", Map.of());

    String clientId   = q.get("client_id");
    int    limit      = parseInt(q.getOrDefault("limit", "50"), 50);
    String beforeDate = q.get("beforeDate");                 // YYYY-MM-DD
    Long   beforeId   = parseLong(q.get("beforeId"), null);  // bigint

    if (clientId == null || clientId.isBlank()) return json(400, Map.of("error","client_id required"));

    String sql = (beforeDate == null || beforeId == null)
        ? "SELECT id, account_id, account_name, merchant, category, amount_cents, post_date, status " +
          "FROM v_transactions WHERE client_id = ?::uuid " +
          "ORDER BY post_date DESC, id DESC LIMIT ?"
        : "SELECT id, account_id, account_name, merchant, category, amount_cents, post_date, status " +
          "FROM v_transactions WHERE client_id = ?::uuid " +
          "AND (post_date, id) < (?::date, ?) " +
          "ORDER BY post_date DESC, id DESC LIMIT ?";

    List<Map<String,Object>> items = new ArrayList<>();

    try (Connection conn = Db.connect();
         PreparedStatement ps = conn.prepareStatement(sql)) {

      ps.setObject(1, java.util.UUID.fromString(clientId));
      int i = 2;
      if (beforeDate != null && beforeId != null) {
        ps.setString(i++, beforeDate);
        ps.setLong(i++,   beforeId);
      }
      ps.setInt(i, limit);

      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String,Object> row = new HashMap<>();
          row.put("id",           rs.getLong("id"));
          row.put("account_id",   rs.getLong("account_id"));
          row.put("account_name", rs.getString("account_name"));
          row.put("merchant",     rs.getString("merchant"));
          row.put("category",     rs.getString("category"));
          row.put("amount_cents", rs.getInt("amount_cents"));
          LocalDate d = rs.getObject("post_date", LocalDate.class);
          row.put("post_date", d == null ? null : d.toString());
          row.put("status",       rs.getString("status"));
          items.add(row);
        }
      }
    } catch (Exception e) {
      return json(500, Map.of("error", e.getMessage()));
    }

    Map<String,Object> next = null;
    if (!items.isEmpty()) {
      Map<String,Object> last = items.get(items.size()-1);
      next = Map.of(
        "beforeDate", Objects.toString(last.get("post_date"), ""),
        "beforeId",   String.valueOf(last.get("id"))
      );
    }
    return json(200, Map.of("items", items, "next", next));
  }

  // --- helpers ---
  private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
  private static Long parseLong(String s, Long def) { try { return (s==null)?def:Long.parseLong(s); } catch (Exception e) { return def; } }
  private static APIGatewayProxyResponseEvent json(int status, Object payload) {
    String body;
    try {
      body = M.writeValueAsString(payload);
    } catch (Exception e) {
      body = "{\"ok\":false}";
    }
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(status)
        .withHeaders(Map.of(
            "Content-Type","application/json",
            "Access-Control-Allow-Origin","*",
            "Access-Control-Allow-Headers","Content-Type, Authorization",
            "Access-Control-Allow-Methods","GET,POST,OPTIONS"
        ))
        .withBody(body);
  }
}