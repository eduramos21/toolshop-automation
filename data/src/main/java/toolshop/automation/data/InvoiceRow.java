package toolshop.automation.data;

import java.math.BigDecimal;

/**
 * A row of the {@code invoices} table, as written by the application.
 *
 * <p>{@link BigDecimal} for the money columns, not {@code double}. The column is
 * {@code decimal}, and reading it as a double reintroduces the rounding the
 * column type exists to avoid - which then shows up as a test that fails by one
 * cent, intermittently, on some totals and not others.
 */
public record InvoiceRow(
        String id,
        String invoiceNumber,
        String billingStreet,
        String billingCity,
        String billingState,
        String billingCountry,
        String billingPostalCode,
        BigDecimal subtotal,
        BigDecimal total,
        String status) {
}
