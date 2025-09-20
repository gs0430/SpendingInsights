package app.plaid;

import com.plaid.client.model.Transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDate;

import static app.common.Db.*;

public class TxUpsertService {
  static int toCents(Number n){ return (int)Math.round(n.doubleValue()*100.0); }
  // Plaid v20 dates are already LocalDate
  static LocalDate d(LocalDate x){ return x; }
  static LocalDate pick(LocalDate a, LocalDate b){ return a != null ? a : b; }
  static String norm(String s){
    return s==null ? null
                   : s.toLowerCase()
                      .replaceAll("[^a-z0-9 ]"," ")
                      .replaceAll("\\s+"," ")
                      .trim();
  }

  static String md5(String s){
    if (s == null) return null;
    try {
      var md = MessageDigest.getInstance("MD5");
      byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(dig.length*2);
      for (byte b : dig) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) { return null; }
  }

  // Returns a single category string for storage.
  static String pickCategory(Transaction t) {
    var pfc = t.getPersonalFinanceCategory();
    if (pfc != null) {
      if (pfc.getPrimary()  != null && !pfc.getPrimary().isBlank()) {
        return pfc.getPrimary().trim()
          .replace('_', ' ');
      }
    }
    return "Uncategorized";
  }

  /** Upsert a single Plaid transaction into the transactions table (insert-only semantics). */
  public static void upsert(Connection c, Transaction t) throws Exception {
    String plaidTxId = t.getTransactionId();
    String plaidAcctId    = t.getAccountId();                     // plaid_account_id
    if (plaidTxId == null || plaidAcctId == null) return; // defensive
    String status    = Boolean.TRUE.equals(t.getPending()) ? "pending" : "posted";
    int amountCents  = toCents(t.getAmount());
    LocalDate auth   = pick(t.getAuthorizedDate(), t.getDate());
    LocalDate post   = d(t.getDate());
    String merchant  = t.getMerchantName()!=null ? t.getMerchantName() : t.getName();
    String mNorm     = norm(merchant);
    String category = pickCategory(t);

    record Link(java.util.UUID clientId, long accountId, String itemId) {}
    final Link link = one(
      c,
      """
      SELECT client_id, account_id, item_id
        FROM account_links
       WHERE plaid_account_id = ?
       ORDER BY last_seen DESC NULLS LAST
       LIMIT 1
      """,
      rs -> {
        try {
          return new Link(
            rs.getObject(1, java.util.UUID.class),
            rs.getLong(2),
            rs.getString(3)
          );
        } catch (Exception e) { throw new RuntimeException(e); }
      },
      plaidAcctId
    );
    if (link == null || link.itemId() == null) return; // mapping not ready; skip safely

    // natural_key_hash required by INSERT column list
    String nkh = md5(
      link.clientId() + "|" + link.accountId() + "|" + amountCents + "|" +
      (post != null ? post.toString() : "") + "|" +
      (merchant != null ? merchant : "")
    );

    // Idempotent UPSERT on (client_id, plaid_tx_id). Avoid null-overwrites via COALESCE.
    exec(c, """
      INSERT INTO transactions (
        client_id, account_id, source_item_id, plaid_tx_id,
        amount_cents, auth_date, post_date, status,
        merchant_norm, merchant_raw, natural_key_hash, category,
        created_at, updated_at
      )
      VALUES (
        ?::uuid, ?, ?, ?,
        ?, ?::date, ?::date, ?,
        ?, ?, ?, ?,
        NOW(), NOW()
      )
      ON CONFLICT (client_id, plaid_tx_id) DO UPDATE
      SET
        account_id     = EXCLUDED.account_id,
        source_item_id = EXCLUDED.source_item_id,
        amount_cents   = EXCLUDED.amount_cents,
        auth_date      = COALESCE(EXCLUDED.auth_date, transactions.auth_date),
        post_date      = COALESCE(EXCLUDED.post_date, transactions.post_date),
        status         = EXCLUDED.status,
        merchant_norm  = COALESCE(EXCLUDED.merchant_norm, transactions.merchant_norm),
        merchant_raw   = COALESCE(EXCLUDED.merchant_raw, transactions.merchant_raw),
        category       = COALESCE(EXCLUDED.category, transactions.category),
        updated_at     = NOW()
      """,
      link.clientId(),            // client_id
      link.accountId(),           // account_id
      link.itemId(),              // source_item_id
      plaidTxId,                  // plaid_tx_id
      amountCents,                // amount_cents
      auth,                       // auth_date
      post,                       // post_date
      status,                     // status
      mNorm,                      // merchant_norm
      merchant,                   // merchant_raw
      nkh,                        // natural_key_hash   <-- added
      category                    // category (NOT NULL)
    );
  }
}