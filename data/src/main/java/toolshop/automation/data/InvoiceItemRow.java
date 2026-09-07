package toolshop.automation.data;

import java.math.BigDecimal;

/** A row of the {@code invoice_items} table. */
public record InvoiceItemRow(
        String id,
        String productId,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal discountedPrice) {
}
