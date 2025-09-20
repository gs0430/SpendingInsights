package app;

import java.math.BigDecimal;
import java.util.List;

public class Models {
  public static class BudgetUpsert {
    public String client_id;
    public List<Item> items;
    public static class Item {
      public String category;
      public BigDecimal monthly_limit;
    }
  }
}
