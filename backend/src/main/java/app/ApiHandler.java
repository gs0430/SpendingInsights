package app;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.plaid.CreateLinkTokenHandler;
import app.plaid.ExchangePublicTokenHandler;
import app.plaid.PlaidWebhookHandler;
import app.plaid.SyncTransactionsHandler;

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
    ds.setURL(System.getenv("jdbc_url")); // jdbc:postgresql://.../db?sslmode=require
    ds.setUser(System.getenv("db_user"));
    ds.setPassword(System.getenv("db_pass"));
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

        // MTD spend per category (posted only). Assumes expenses are stored as positive cents.
        // If your expenses are negative, change the CASE to "WHEN t.amount_cents < 0 THEN -t.amount_cents ELSE 0".
        String sql =
          "WITH bounds AS (\n" +
          "  SELECT date_trunc('month', CURRENT_DATE)::date AS start_date,\n" +
          "         CURRENT_DATE::date AS end_date\n" +
          ")\n" +
          "SELECT b.category,\n" +
          "       b.monthly_limit,\n" +
          "       (GREATEST(COALESCE(SUM(t.amount_cents), 0), 0) / 100.0) AS current_spend\n" +  // net of refunds, floored at 0
          "FROM budget b\n" +
          "LEFT JOIN transactions t\n" +
          "  ON t.client_id = b.client_id\n" +
          " AND t.category  = b.category\n" +
          " AND t.post_date BETWEEN (SELECT start_date FROM bounds) AND (SELECT end_date FROM bounds)\n" +
          " AND COALESCE(t.status,'') <> 'PENDING'\n" +
          "WHERE b.client_id = ?::uuid\n" +
          "GROUP BY b.category, b.monthly_limit\n" +
          "ORDER BY b.category";

        try (Connection conn = DS.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.setObject(1, java.util.UUID.fromString(clientId));
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              rows.add(Map.of(
                "category",      rs.getString("category"),
                "monthly_limit", rs.getBigDecimal("monthly_limit"), // dollars
                "current_spend", rs.getBigDecimal("current_spend")  // dollars (MTD)
              ));
            }
          }
        } catch (Exception e) {
          return corsJson(500, Map.of("error", e.getMessage()));
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

      // DELETE /v1/budgets?client_id=...&category=...
      if ("DELETE".equalsIgnoreCase(method) && "/v1/budgets".equals(path)) {
        Map<String,String> q = req.getQueryStringParameters()==null ? Map.of() : req.getQueryStringParameters();
        String clientId = q.get("client_id"), category = q.get("category");
        if (clientId==null || clientId.isBlank()) return corsJson(400, Map.of("error","client_id required"));
        if (category==null || category.isBlank()) return corsJson(400, Map.of("error","category required"));

        int deleted = 0;
        try (Connection conn = DS.getConnection();
            PreparedStatement ps = conn.prepareStatement(
              "DELETE FROM budget WHERE client_id = ?::uuid AND category = ?")) {
          ps.setObject(1, java.util.UUID.fromString(clientId));
          ps.setString(2, category);
          deleted = ps.executeUpdate();
        } catch (Exception e) {
          return corsJson(500, Map.of("error", e.getMessage()));
        }
        return corsJson(200, Map.of("status","ok","deleted",deleted));
      }

      // POST /api/plaid/link-token/create
      if ("POST".equalsIgnoreCase(method) && "/api/plaid/link-token/create".equals(path)) {
        Map<String,Object> in = Map.of("body", req.getBody() == null ? "{}" : req.getBody());
        APIGatewayProxyResponseEvent r = new CreateLinkTokenHandler().handleRequest(in, ctx);
        APIGatewayV2HTTPResponse resp = adapt(r);
        return resp;
      }

      // POST /api/plaid/item/public_token/exchange
      if ("POST".equalsIgnoreCase(method) && "/api/plaid/item/public_token/exchange".equals(path)) {
        Map<String,Object> in = Map.of("body", req.getBody() == null ? "{}" : req.getBody());
        APIGatewayProxyResponseEvent r = new ExchangePublicTokenHandler().handleRequest(in, ctx);
        APIGatewayV2HTTPResponse resp = adapt(r);
        ctx.getLogger().log("[DBG] /api/plaid/item/public_token/exchange resp headers: " + resp.getHeaders() + "\n");
        return resp;
      }

      // POST /api/plaid/transactions/sync
      if ("POST".equalsIgnoreCase(method) && "/api/plaid/transactions/sync".equals(path)) {
        Map<String,Object> in = Map.of("body", req.getBody() == null ? "{}" : req.getBody());
        APIGatewayProxyResponseEvent r = new SyncTransactionsHandler().handleRequest(in, ctx);
        APIGatewayV2HTTPResponse resp = adapt(r);
        ctx.getLogger().log("[DBG] /api/plaid/transactions/sync resp headers: " + resp.getHeaders() + "\n");
        return resp;
      }

      // POST /webhooks/plaid
      if ("POST".equalsIgnoreCase(method) && "/webhooks/plaid".equals(path)) {
        Map<String,Object> in = Map.of("body", req.getBody() == null ? "{}" : req.getBody());
        APIGatewayProxyResponseEvent r = new PlaidWebhookHandler().handleRequest(in, ctx);
        APIGatewayV2HTTPResponse resp = adapt(r);
        ctx.getLogger().log("[DBG] /webhooks/plaid resp headers: " + resp.getHeaders() + "\n");
        return resp;
      }

      if ("GET".equalsIgnoreCase(method) && "/v1/transactions".equals(path)) {
        Map<String, String> q = req.getQueryStringParameters() == null ? Map.of() : req.getQueryStringParameters();
        String clientId = q.get("client_id");
        if (clientId == null || clientId.isBlank()) {
          return corsJson(400, Map.of("error", "client_id required"));
        }
        // Forward query params to the sub-handler (v1-style input)
        Map<String,Object> forward = new HashMap<>();
        forward.put("queryStringParameters", q);

        APIGatewayProxyResponseEvent r = new ListTransactionsHandler().handleRequest(forward, ctx);
        return adapt(r);
      }

      return corsJson(404, Map.of("error", "not found"));
    } catch (Exception e) {
      return corsJson(500, Map.of("error", e.getMessage()));
    }
  }

  private static APIGatewayV2HTTPResponse adapt(APIGatewayProxyResponseEvent v1) {
    Map<String, String> h = new HashMap<>(corsHeaders());
    if (v1.getHeaders() != null) {
        h.putAll(v1.getHeaders());
    }
    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(v1.getStatusCode() == null ? 200 : v1.getStatusCode())
        .withHeaders(h)
        .withBody(v1.getBody())
        .build();
  }

  private static Map<String,String> corsHeaders() {
    Map<String,String> h = new HashMap<>();
    h.put("Content-Type","application/json");
    h.put("Access-Control-Allow-Origin", ALLOWED_ORIGINS);
    h.put("Access-Control-Allow-Headers","Content-Type, Authorization");
    h.put("Access-Control-Allow-Methods","GET,POST,DELETE,OPTIONS");
    return h;
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
    headers.put("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(code)
        .withHeaders(headers)
        .withBody(body)
        .build();
  }
}
