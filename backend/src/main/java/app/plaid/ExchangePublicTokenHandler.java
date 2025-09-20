package app.plaid;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.client.request.PlaidApi;
import com.plaid.client.model.*;
import retrofit2.Response;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

import static app.common.Db.*;

public class ExchangePublicTokenHandler implements RequestHandler<Map<String,Object>, APIGatewayProxyResponseEvent> {
  private static final ObjectMapper M = new ObjectMapper();

  // request from frontend: { clientId, publicToken, institutionId }
  static class Req {
    public String clientId;       // UUID string
    public String publicToken;    // from Plaid Link
  }
  static class Res {
    public boolean ok = true;
    public String itemId;
    public String institutionId;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(Map<String,Object> in, Context ctx) {
    try {
      String raw = (String) in.getOrDefault("body", "{}");
      Req req = M.readValue(raw, Req.class);

      if (req == null || req.clientId == null || req.clientId.isBlank()) {
        return json(400, Map.of("ok", false, "error", "clientId required"));
      }
      if (req.publicToken == null || req.publicToken.isBlank()) {
        return json(400, Map.of("ok", false, "error", "publicToken required"));
      }

      PlaidApi plaid = PlaidClientFactory.client();
      ItemPublicTokenExchangeRequest body = new ItemPublicTokenExchangeRequest().publicToken(req.publicToken);
      Response<ItemPublicTokenExchangeResponse> resp = plaid.itemPublicTokenExchange(body).execute();

      if (!resp.isSuccessful() || resp.body() == null) {
        String errBody = resp.errorBody() != null ? resp.errorBody().string() : "null";
        ctx.getLogger().log("[Exchange] Plaid error " + resp.code() + ": " + errBody);
        return json(resp.code() == 400 ? 400 : 502, Map.of("ok", false, "error","plaid_failed","details", errBody));
      }

      String accessToken = resp.body().getAccessToken();
      String itemId = resp.body().getItemId();

      String instId = plaid.itemGet(new ItemGetRequest().accessToken(accessToken))
        .execute().body().getItem().getInstitutionId();

      // Secret per ITEM (token only)
      String sid = "plaid/access-token/%s/%s".formatted(req.clientId, itemId);
      SecretsManagerClient sm = SecretsManagerClient.create();
      try {
        sm.putSecretValue(PutSecretValueRequest.builder()
            .secretId(sid)
            .secretString(accessToken)
            .build());
      } catch (ResourceNotFoundException rnfe) {
        sm.createSecret(CreateSecretRequest.builder()
            .name(sid)
            .secretString(accessToken)
            .build());
      }

      // upsert accounts here, fetch them now.
      List<AccountBase> tmpAccounts = Collections.emptyList();

      try {
        var ag = new com.plaid.client.model.AccountsGetRequest().accessToken(accessToken);
        var agResp = plaid.accountsGet(ag).execute();
        if (agResp.isSuccessful() && agResp.body() != null) {
          tmpAccounts = agResp.body().getAccounts();
        }
      } catch (Exception ignore) {
        // safe to proceed; accounts can be synced later
      }
      final List<AccountBase> accountsForTx = tmpAccounts;  // <-- effectively final

      List<String> oldItemIdsForSecrets = new ArrayList<>();
      // --- DB writes ---
      withTx(conn -> {
        // 1) Ensure client exists
        exec(conn, """
          INSERT INTO client (client_id)
          VALUES (?::uuid)
          ON CONFLICT (client_id) DO NOTHING
        """, req.clientId);

        // Deactivate any previously-active Items for this (client, institution) that are not this new itemId
        exec(conn, """
          UPDATE items
            SET is_active = FALSE
          WHERE client_id = ?::uuid
            AND institution_id = ?
            AND item_id <> ?
            AND is_active
        """, req.clientId, instId, itemId);

        // Record/refresh the item row
        //    (relies on PK (client_id, item_id) and ON UPDATE CASCADE in FKs)
        exec(conn, """
          INSERT INTO items (client_id, item_id, institution_id)
          VALUES (?::uuid, ?, ?)
          ON CONFLICT (client_id, item_id) DO UPDATE
            SET institution_id = EXCLUDED.institution_id,
              last_linked_at = NOW(),
              is_active      = TRUE
        """, req.clientId, itemId, instId);

        // 3) Accounts + Links (link Plaid account → internal accounts.id)
        for (AccountBase a : accountsForTx) {
          String plaidAccountId = a.getAccountId();
          String name    = (a.getOfficialName() != null && !a.getOfficialName().isBlank())
                            ? a.getOfficialName() : a.getName();
          String subtype = (a.getSubtype() != null) ? a.getSubtype().getValue() : null;
          String mask    = a.getMask();

          // Reuse account by (client_id, current_plaid_account_id)
          Long accountId = one(conn, """
            SELECT id
              FROM accounts
            WHERE client_id = ?::uuid
              AND current_plaid_account_id = ?
            LIMIT 1
          """, rs -> rs.getLong(1), req.clientId, plaidAccountId);

          if (accountId == null) {
            // Create account, get id
            accountId = one(conn, """
              INSERT INTO accounts (client_id, institution_id, current_item_id, current_plaid_account_id, name, mask, subtype)
              VALUES (?::uuid, ?, ?, ?, ?, ?, ?)
              RETURNING id
            """, rs -> rs.getLong(1), req.clientId, instId, itemId, plaidAccountId, name, mask, subtype);
          } else {
            exec(conn, """
              UPDATE accounts
                SET name                     = ?,
                    mask                     = ?,
                    subtype                  = ?,
                    institution_id           = ?,
                    current_item_id          = ?,
                    current_plaid_account_id = ?,
                    last_seen                = NOW(),
                    is_active                = TRUE
              WHERE id = ?
                AND client_id = ?::uuid
            """, name, mask, subtype, instId, itemId, plaidAccountId, accountId, req.clientId);
          }

          // Link (client,item,plaid_account) → account_id
          exec(conn, """
            INSERT INTO account_links (client_id, item_id, plaid_account_id, account_id)
            VALUES (?::uuid, ?, ?, ?)
            ON CONFLICT (client_id, item_id, plaid_account_id) DO UPDATE
              SET account_id = EXCLUDED.account_id,
                  last_seen  = NOW()
          """, req.clientId, itemId, plaidAccountId, accountId);

          // We will consider accounts “the same” if (institution_id, mask, subtype) match
          // Move transactions from any *old* account with same key → this new/updated accountId
          exec(conn, """
            UPDATE transactions t
              SET account_id = ?
            WHERE t.client_id = ?::uuid
              AND t.account_id IN (
                SELECT ao.id
                  FROM accounts ao
                  WHERE ao.client_id      = ?::uuid
                    AND ao.institution_id = ?
                    AND COALESCE(ao.mask,    '') = COALESCE(?, '')
                    AND COALESCE(ao.subtype, '') = COALESCE(?, '')
                    AND ao.id <> ?
              )
          """,
            accountId,               // 1) target account
            req.clientId,            // 2) t.client_id
            req.clientId,            // 3) ao.client_id
            instId,                  // 4) same bank
            mask,                    // 5) same mask
            subtype,                 // 6) same subtype
            accountId                // 7) exclude the target
          );
        }

        // 2a) collect old (now inactive) items BEFORE you delete them
        final List<String> oldItemIds = new ArrayList<>();
        try (var ps = conn.prepareStatement("""
          SELECT item_id
            FROM items
          WHERE client_id = ?::uuid
            AND institution_id = ?
            AND item_id <> ?
            AND is_active = FALSE
        """)) {
          ps.setString(1, req.clientId);
          ps.setString(2, instId);
          ps.setString(3, itemId);
          try (var rs = ps.executeQuery()) {
            while (rs.next()) oldItemIds.add(rs.getString(1));
          }
        }

        // 2c) Repoint transactions' source_item_id from old Items -> new Item
        exec(conn, """
          UPDATE transactions
            SET source_item_id = ?
          WHERE client_id = ?::uuid
            AND source_item_id IN (
              SELECT item_id
                FROM items
                WHERE client_id = ?::uuid
                  AND institution_id = ?
                  AND item_id <> ?
                  AND is_active = FALSE
            )
        """, itemId, req.clientId, req.clientId, instId, itemId);

        // 2d) Delete old (inactive) Items for this bank (account_links will cascade)
        exec(conn, """
          DELETE FROM items
          WHERE client_id = ?::uuid
            AND institution_id = ?
            AND item_id <> ?
            AND is_active = FALSE
        """, req.clientId, instId, itemId);

        // 2e) Delete orphaned Accounts (no tx, no links) left behind
        exec(conn, """
          DELETE FROM accounts a
          WHERE a.client_id = ?::uuid
            AND a.institution_id = ?
            AND NOT EXISTS (
                  SELECT 1 FROM transactions t
                    WHERE t.client_id = a.client_id
                      AND t.account_id = a.id
              )
            AND NOT EXISTS (
                  SELECT 1 FROM account_links l
                    WHERE l.client_id = a.client_id
                      AND l.account_id = a.id
              )
        """, req.clientId, instId);

        // stash for post-commit secret cleanup
        oldItemIdsForSecrets.addAll(oldItemIds);

        return null;
      });

      for (String oldItemId : oldItemIdsForSecrets) {
        String oldSid = "plaid/access-token/%s/%s".formatted(req.clientId, oldItemId);
        try {
          var get = sm.getSecretValue(GetSecretValueRequest.builder().secretId(oldSid).build());
          String oldAccessToken = get.secretString();
          // Revoke old token
          try {
            plaid.itemRemove(new com.plaid.client.model.ItemRemoveRequest()
              .accessToken(oldAccessToken)).execute();
          } catch (Exception ignore) {}
          // Delete old secret
          try {
            sm.deleteSecret(b -> b.secretId(oldSid).forceDeleteWithoutRecovery(true));
          } catch (Exception ignore) {}
        } catch (ResourceNotFoundException ignore) {
          // no secret to clean
        }
      }

      ctx.getLogger().log("[Exchange] stored access_token for client=" + req.clientId + " item=" + itemId);

      return json(200, Map.of("ok", true, "itemId", itemId, "institutionId", instId));
    } catch (Exception e) {
      ctx.getLogger().log("[Exchange] internal error: " + e);
       return json(500, Map.of("ok", false, "error","internal"));
    }
  }

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