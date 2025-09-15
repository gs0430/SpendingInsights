package app;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final ObjectMapper M = new ObjectMapper();
  private static final DataSource DS = initDS();
  private static final String ALLOWED_ORIGINS = Optional.ofNullable(System.getenv("ALLOWED_ORIGINS")).orElse("*");

  private static DataSource initDS() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setURL(System.getenv("JDBC_URL")); // jdbc:postgresql://.../db?sslmode=require
    ds.setUser(System.getenv("DB_USER"));
    ds.setPassword(System.getenv("DB_PASSWORD"));
    return ds;
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent req, Context ctx) {
    try {
      String path = req.getRawPath() == null ? "" : req.getRawPath();
      String method = req.getRequestContext().getHttp().getMethod();

      // Remove stage prefix (e.g., "/prod") if present
      String stage = req.getRequestContext().getStage();
      if (stage != null && !stage.isBlank()) {
        String prefix = "/" + stage;
        if (path.startsWith(prefix)) {
          path = path.substring(prefix.length());
          if (path.isEmpty()) path = "/";
        }
      }

      // CORS preflight
      if ("OPTIONS".equalsIgnoreCase(method)) {
        return cors(200, "{}");
      }

      // Health
      if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) {
        Map<String, Object> out = new HashMap<>();
        out.put("status", "ok");
        out.put("time", new Date().toString());
        return corsJson(200, out);
      }

      if (method.equalsIgnoreCase("GET") && "/v1/budgets".equals(path)) {
        Map<String, String> q = req.getQueryStringParameters() == null ? Map.of() : req.getQueryStringParameters();
        String clientId = q.get("client_id");
        if (clientId == null || clientId.isBlank()) {
          return corsJson(400, Map.of("error", "client_id required"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DS.getConnection();
             PreparedStatement ps = conn.prepareStatement("select category, monthly_limit from budget where client_id = ? order by category")) {
          ps.setObject(1, java.util.UUID.fromString(clientId));
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              rows.add(Map.of("category", rs.getString(1), "monthly_limit", rs.getBigDecimal(2)));
            }
          }
        }
        return corsJson(200, rows);
      }

      if (method.equalsIgnoreCase("POST") && "/v1/budgets".equals(path)) {
        Models.BudgetUpsert payload = M.readValue(req.getBody(), Models.BudgetUpsert.class);
        if (payload.client_id == null || payload.client_id.isBlank()) {
          return corsJson(400, Map.of("error", "client_id required"));
        }
        UUID cid;
        try {
          cid = UUID.fromString(payload.client_id);
        } catch (Exception ex) {
          return corsJson(400, Map.of("error", "client_id must be a UUID v4"));
        }

        try (Connection conn = DS.getConnection()) {
          conn.setAutoCommit(false);

          // 1) ensure client row exists
          try (PreparedStatement psClient = conn.prepareStatement(
            "insert into client(client_id) values (?) on conflict (client_id) do nothing")) {
            psClient.setObject(1, cid);
            psClient.executeUpdate();
          }

          // 2) upsert budgets
          try (PreparedStatement ps = conn.prepareStatement(
            "insert into budget(client_id, category, monthly_limit) values (?, ?, ?) " +
            "on conflict (client_id, category) do update set monthly_limit = excluded.monthly_limit")) {
            for (Models.BudgetUpsert.Item it : payload.items) {
              ps.setObject(1, cid);
              ps.setString(2, it.category);
              ps.setBigDecimal(3, it.monthly_limit);
              ps.addBatch();
            }
            ps.executeBatch();
          }

          conn.commit();
        }
        return corsJson(200, Map.of("status", "ok"));

      }

      // POST /v1/spend  -> upsert spend_current rows
      if ("POST".equalsIgnoreCase(method) && "/v1/spend".equals(path)) {
        Models.SpendUpsert payload = M.readValue(req.getBody(), Models.SpendUpsert.class);
        if (payload.client_id == null || payload.client_id.isBlank()) {
          return corsJson(400, Map.of("error", "client_id required"));
        }
        java.util.UUID cid;
        try { cid = java.util.UUID.fromString(payload.client_id); }
        catch (Exception e) { return corsJson(400, Map.of("error", "client_id must be a UUID v4")); }

        try (Connection conn = DS.getConnection()) {
          conn.setAutoCommit(false);

          // ensure client exists (your client PK column is client_id)
          try (PreparedStatement psC = conn.prepareStatement(
            "insert into client(client_id) values (?) on conflict (client_id) do nothing")) {
            psC.setObject(1, cid);
            psC.executeUpdate();
          }

          // upsert spend_current
          try (PreparedStatement ps = conn.prepareStatement(
            "insert into spend_current(client_id, category, amount) values (?, ?, ?) " +
            "on conflict (client_id, category) do update set amount = excluded.amount")) {
            if (payload.items != null) {
              for (Models.SpendUpsert.Item it : payload.items) {
                ps.setObject(1, cid);
                ps.setString(2, it.category);
                ps.setBigDecimal(3, it.amount);
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }

          conn.commit();
        } catch (Exception ex) {
          return corsJson(500, Map.of("error", ex.getMessage()));
        }
        return corsJson(200, Map.of("status", "ok"));
      }

      // GET /v1/insights?client_id=UUID  -> join budgets with spend + status
      if ("GET".equalsIgnoreCase(method) && "/v1/insights".equals(path)) {
        Map<String, String> q = req.getQueryStringParameters();
        String clientId = (q != null) ? q.get("client_id") : null;
        if (clientId == null || clientId.isBlank()) {
          return corsJson(400, Map.of("error", "client_id required"));
        }
        java.util.UUID cid;
        try { cid = java.util.UUID.fromString(clientId); }
        catch (Exception e) { return corsJson(400, Map.of("error", "client_id must be a UUID v4")); }

        String sql =
          "select b.category, b.monthly_limit, coalesce(s.amount,0) as spent, " +
          "case " +
          "  when coalesce(s.amount,0) >= b.monthly_limit then 'OVER' " +
          "  when coalesce(s.amount,0) >= 0.8*b.monthly_limit then 'WARNING' " +
          "  else 'OK' " +
          "end as status " +
          "from budget b " +
          "left join spend_current s on s.client_id=b.client_id and s.category=b.category " +
          "where b.client_id = ? " +
          "order by b.category asc";

        java.util.List<Map<String,Object>> out = new java.util.ArrayList<>();
        try (Connection conn = DS.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.setObject(1, cid);
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              Map<String,Object> row = new java.util.HashMap<>();
              row.put("category", rs.getString("category"));
              row.put("monthly_limit", rs.getBigDecimal("monthly_limit"));
              row.put("spent", rs.getBigDecimal("spent"));
              row.put("status", rs.getString("status"));
              out.add(row);
            }
          }
        }
        return corsJson(200, out);
      }

      return corsJson(404, Map.of("error", "not found"));
    } catch (Exception e) {
      return corsJson(500, Map.of("error", e.getMessage()));
    }
  }

  private static APIGatewayV2HTTPResponse corsJson(int code, Object obj) {
    try {
      String body = new ObjectMapper().writeValueAsString(obj);
      return cors(code, body);
    } catch (Exception e) {
      return cors(500, "{\"error\":\"serialize\"}");
    }
  }

  private static APIGatewayV2HTTPResponse cors(int code, String body) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Access-Control-Allow-Origin", ALLOWED_ORIGINS);
    headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
    headers.put("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(code)
        .withHeaders(headers)
        .withBody(body)
        .build();
  }
}
