package app.plaid;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.client.request.PlaidApi;
import com.plaid.client.model.*;
import retrofit2.Response;

import java.util.List;
import java.util.Map;

public class CreateLinkTokenHandler implements RequestHandler<Map<String,Object>, APIGatewayProxyResponseEvent> {
  private static final ObjectMapper M = new ObjectMapper();
  record Req(String clientId) {}
  record Res(String linkToken) {}

  @Override
  public APIGatewayProxyResponseEvent handleRequest(Map<String,Object> in, Context ctx) {
    try {
      var body = (String) in.getOrDefault("body","{}");
      var req = M.readValue(body, Req.class);
      if (req == null || req.clientId == null || req.clientId.isBlank()) {
        return json(400, Map.of("error","clientId required"));
      }
      
      PlaidApi plaid = PlaidClientFactory.client();

      var user = new LinkTokenCreateRequestUser()
          .clientUserId(req.clientId);

      var filters = new LinkTokenAccountFilters()
        .depository(new DepositoryFilter().accountSubtypes(
            List.of(DepositoryAccountSubtype.CHECKING, DepositoryAccountSubtype.SAVINGS)))
        .credit(new CreditFilter().accountSubtypes(
            List.of(CreditAccountSubtype.CREDIT_CARD)));

      var ltReq = new LinkTokenCreateRequest()
        .user(user)
        .clientName("Spending Insight")
        .products(List.of(Products.TRANSACTIONS))
        .countryCodes(List.of(CountryCode.US))
        .language("en")
        .webhook(System.getenv("plaid_webhook_url"))
        .accountFilters(filters);

      String redirectUri = System.getenv("plaid_redirect_uri");
      if (redirectUri != null && !redirectUri.isBlank()) {
        ltReq.redirectUri(redirectUri);
      }

      Response<LinkTokenCreateResponse> resp = plaid.linkTokenCreate(ltReq).execute();

      if (!resp.isSuccessful() || resp.body() == null) {
        String err = "plaid_failed";
        try {
          if (resp.errorBody() != null) err = resp.errorBody().string();
        } catch (Exception ignore) {}
        return json(502, Map.of("error", err, "status", resp.code()));
      }

      return json(200, new Res(resp.body().getLinkToken()));

    } catch (Exception e) {
      // Do NOT call a method that throws here; return a safe JSON response
      return json(500, Map.of("error","internal"));
    }
  }

  private static APIGatewayProxyResponseEvent json(int status, Object payload) {
    String body;
    try {
      body = M.writeValueAsString(payload);
    } catch (Exception e) {
      // last-resort fallback
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
