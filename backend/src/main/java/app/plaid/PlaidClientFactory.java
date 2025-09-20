package app.plaid;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;

import java.util.HashMap;

public class PlaidClientFactory {

  public static PlaidApi client() {
    // API keys for all calls
    HashMap<String, String> apiKeys = new HashMap<>();
    apiKeys.put("clientId", System.getenv("plaid_client_id"));
    apiKeys.put("secret",   System.getenv("plaid_secret"));

    ApiClient apiClient = new ApiClient(apiKeys);

    String env = System.getenv("plaid_env");
    String e = env == null ? "SANDBOX" : env.toUpperCase();

    switch (e) {
      case "PRODUCTION" -> apiClient.setPlaidAdapter(ApiClient.Production);
      default -> apiClient.setPlaidAdapter(ApiClient.Sandbox);
    }

    return apiClient.createService(PlaidApi.class);
  }
}