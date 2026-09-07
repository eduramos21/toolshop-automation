package toolshop.automation.api.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * {@code POST /invoices}.
 *
 * <p>{@code payment_details} is a {@code Map} and not a record, because the API
 * declares it as a free-form object whose shape depends on
 * {@code payment_method}. Modelling it as five records would be inventing a
 * contract the application does not have.
 */
public record InvoiceRequest(
        @JsonProperty("billing_street") String billingStreet,
        @JsonProperty("billing_city") String billingCity,
        @JsonProperty("billing_state") String billingState,
        @JsonProperty("billing_country") String billingCountry,
        @JsonProperty("billing_postal_code") String billingPostalCode,
        @JsonProperty("payment_method") PaymentMethod paymentMethod,
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("payment_details") Map<String, Object> paymentDetails) {

    /** The same billing address, paid for a different way. */
    public InvoiceRequest payingWith(PaymentMethod method, Map<String, Object> details) {
        return new InvoiceRequest(billingStreet, billingCity, billingState, billingCountry,
                billingPostalCode, method, cartId, details);
    }

    public InvoiceRequest forCart(String otherCartId) {
        return new InvoiceRequest(billingStreet, billingCity, billingState, billingCountry,
                billingPostalCode, paymentMethod, otherCartId, paymentDetails);
    }
}
