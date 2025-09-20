package app.plaid;


import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.client.model.TransactionsSyncRequest;
import static app.common.Db.*;
import java.sql.Connection;
import java.util.*;

public class PlaidWebhookHandler implements RequestHandler<Map<String,Object>, APIGatewayProxyResponseEvent> {
  private static final ObjectMapper M = new ObjectMapper();

  @Override public APIGatewayProxyResponseEvent handleRequest(Map<String,Object> in, Context ctx) {
    try {
      var body = (String) in.getOrDefault("body","{}");
      @SuppressWarnings("unchecked")
      var payload = M.readValue(body, Map.class);
      var type = String.valueOf(payload.get("webhook_type"));
      var itemId = String.valueOf(payload.get("item_id"));
      if (!"TRANSACTIONS".equalsIgnoreCase(type)) return ok(); // ignore others

      var plaid = PlaidClientFactory.client();
      withTx((Connection c) -> {
        var row = one(c, "SELECT access_token, cursor FROM plaid_items WHERE item_id = ?",
          rs -> new String[]{rs.getString(1), rs.getString(2)}, itemId);
        if (row==null) return null;
        String token=row[0], cursor=row[1];
        boolean more=true;
        while (more) {
          var sreq = new TransactionsSyncRequest().accessToken(token).cursor(cursor);
          var sres = plaid.transactionsSync(sreq).execute().body();
          for (var t : sres.getAdded())    TxUpsertService.upsert(c, t);
          for (var t : sres.getModified()) TxUpsertService.upsert(c, t);
          cursor = sres.getNextCursor();
          more = Boolean.TRUE.equals(sres.getHasMore());
        }
        exec(c, "UPDATE plaid_items SET cursor=? WHERE item_id=?", cursor, itemId);
        return null;
      });

      return ok();
    } catch (Exception e) { return new APIGatewayProxyResponseEvent().withStatusCode(500); }
  }

  private APIGatewayProxyResponseEvent ok(){
    return new APIGatewayProxyResponseEvent()
      .withStatusCode(200)
      .withHeaders(Map.of("Content-Type","application/json","Access-Control-Allow-Origin","*"))
      .withBody("{\"ok\":true}");
  }
}
