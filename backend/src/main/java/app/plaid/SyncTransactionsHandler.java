package app.plaid;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.plaid.client.request.PlaidApi;
import com.plaid.client.model.*;

import retrofit2.Response;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import static app.common.Db.*;

public class SyncTransactionsHandler implements RequestHandler<Map<String,Object>, APIGatewayProxyResponseEvent> {

  private static final ObjectMapper M = new ObjectMapper();
  record Req(String clientId) {}
  record Res(int upserted) {}

  private static final SecretsManagerClient SM = SecretsManagerClient.builder().build();
  private static String secretId(String clientId, String itemId) {
    return "plaid/access-token/%s/%s".formatted(clientId, itemId);
  }
  private static String getAccessTokenByItem(String clientId, String itemId) {
    try {
      var resp = SM.getSecretValue(GetSecretValueRequest.builder()
        .secretId(secretId(clientId, itemId))
        .build());
      return resp.secretString();
    } catch (ResourceNotFoundException rnfe) {
      return null;
    }
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(Map<String,Object> in, Context ctx) {
    try (Connection conn = connect()) {
      // parse body
      var body = (String) in.getOrDefault("body", "{}");
      var req  = M.readValue(body, Req.class);
      if (req == null || req.clientId == null || req.clientId.isBlank()) {
        return json(400, Map.of("error","clientId required"));
      }

      // Find the newest ACTIVE Item for this client
      String itemId = one(conn,
        "SELECT item_id FROM items " +
        "WHERE client_id = ?::uuid AND is_active " +
        "ORDER BY last_linked_at DESC LIMIT 1",
        rs -> rs.getString(1),
        req.clientId
      );
      if (itemId == null || itemId.isBlank()) {
        return json(404, Map.of("error","no_active_item_for_client"));
      }

      // Retrieve access_token from Secrets Manager by (clientId, itemId)
      String accessToken = getAccessTokenByItem(req.clientId, itemId);
      if (accessToken == null || accessToken.isBlank()) {
        return json(404, Map.of("error","no_access_token_secret","itemId", itemId));
      }

      PlaidApi plaid = PlaidClientFactory.client();

      // Pull last 30 days for now (simpler than /transactions/sync; you can upgrade later)
      LocalDate end   = LocalDate.now();
      LocalDate start = end.minusDays(30);

      TransactionsGetRequest tgReq = new TransactionsGetRequest()
          .accessToken(accessToken)
          .startDate(start)
          .endDate(end);

      Response<TransactionsGetResponse> tgResp = plaid.transactionsGet(tgReq).execute();

      if (!tgResp.isSuccessful() || tgResp.body() == null) {
        String err = "plaid_transactions_failed";
        try { if (tgResp.errorBody() != null) err = tgResp.errorBody().string(); } catch (Exception ignore) {}
        return json(502, Map.of("error", err));
      }

      List<Transaction> txs = tgResp.body().getTransactions();
      int upserted = 0;

      // Upsert each transaction
      for (Transaction t : txs) {
        try {
          TxUpsertService.upsert(conn, t);
          upserted++;
        } catch (Exception e) {
          // Log to CloudWatch but keep processing the rest
          String txId = null;
          try { txId = t.getTransactionId(); } catch (Exception ignore) {}
          ctx.getLogger().log(
              "[Sync] upsert failed"
              + (txId != null ? " txId=" + txId : "")
              + " err=" + e.toString()
              + "\n" + stack(e)
    );
        }
      }

      return json(200, new Res(upserted));

    } catch (Exception e) {
      ctx.getLogger().log("[Sync] fatal error: " + e.toString() + "\n" + stack(e));
      System.err.println("[Sync] fatal error: " + e);
      System.err.println(stack(e));
      return json(500, Map.of("error","internal"));
    }
  }

  private static APIGatewayProxyResponseEvent json(int status, Object payload) {
    String body;
    try { body = M.writeValueAsString(payload); }
    catch (Exception ignore) { body = "{\"ok\":false}"; }
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

  private static String stack(Throwable t) {
    java.io.StringWriter sw = new java.io.StringWriter();
    t.printStackTrace(new java.io.PrintWriter(sw));
    return sw.toString();
  }
}