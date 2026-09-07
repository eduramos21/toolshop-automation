package toolshop.automation.api.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code POST /carts/{id}} - add or update an item. */
public record CartItem(@JsonProperty("product_id") String productId, int quantity) {
}
