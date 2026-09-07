package toolshop.automation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static toolshop.automation.api.ApiAssertions.assertThatStatus;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import toolshop.automation.api.payload.InvoiceRequest;
import toolshop.automation.api.payload.PaymentMethod;
import toolshop.automation.api.payload.PostcodeLookup;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.tags.Api;
import toolshop.automation.core.tags.Checkout;
import toolshop.automation.core.tags.Regression;
import toolshop.automation.core.tags.Smoke;

/**
 * The API half of the checkout slice: cart to invoice.
 *
 * <p>The billing address comes from {@code GET /postcode-lookup} rather than
 * from a literal, because {@code POST /invoices} validates the city and state
 * against exactly that lookup. Hardcoding "Amsterdam" here would fail against
 * every target, and would keep failing for a reason that reads like a bug in the
 * application.
 */
@Api
@Checkout
@Epic("Checkout")
@Feature("Invoicing")
class CheckoutApiTest {

    private final ToolshopApi api;
    private final ToolshopConfig config;

    CheckoutApiTest(ToolshopApi api, ToolshopConfig config) {
        this.api = api;
        this.config = config;
    }

    @Test
    @Smoke
    @Story("A customer can check out a cart and receive an invoice")
    @Severity(SeverityLevel.BLOCKER)
    void aCustomerCanCheckOutACartAndReceiveAnInvoice() {
        // Given an authenticated customer with a cart holding two of one product
        String productId = api.someProductInStock();
        double unitPrice = api.anonymous().get("/products/" + productId)
                .jsonPath().getDouble("price");
        String cartId = api.cartContaining(productId, 2);

        // When they check out by credit card
        Response response = api.as(config.customer())
                .body(validInvoiceFor(cartId, PaymentMethod.CREDIT_CARD))
                .post("/invoices");

        // Then an invoice is raised for the value of the cart
        assertThatStatus(response).isEqualTo(201);
        assertThat(response.jsonPath().getString("invoice_number"))
                .as("invoice numbers are sequential and prefixed")
                .startsWith("INV-");
        assertThat(response.jsonPath().getDouble("total"))
                .as("two of a product priced %s", unitPrice)
                .isEqualTo(unitPrice * 2, org.assertj.core.data.Offset.offset(0.01));
        assertThat(response.jsonPath().getString("billing_country")).isEqualTo("NL");
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    @Regression
    @Story("Every payment method the API advertises produces an invoice")
    @Severity(SeverityLevel.CRITICAL)
    void everyAdvertisedPaymentMethodProducesAnInvoice(PaymentMethod method) {
        // Given a cart per case, since an invoice consumes one
        String cartId = api.cartContaining(api.someProductInStock(), 1);

        // When the cart is checked out with this method
        Response response = api.as(config.customer())
                .body(validInvoiceFor(cartId, method))
                .post("/invoices");

        // Then it is accepted
        assertThatStatus(response).isEqualTo(201);
        assertThat(response.jsonPath().getString("invoice_number")).startsWith("INV-");
    }

    @Test
    @Story("Checking out requires authentication")
    @Severity(SeverityLevel.CRITICAL)
    void checkingOutRequiresAuthentication() {
        // Given a valid cart and a valid invoice, but no credentials
        String cartId = api.cartContaining(api.someProductInStock(), 1);

        // When it is submitted anonymously
        Response response = api.anonymous()
                .body(validInvoiceFor(cartId, PaymentMethod.CASH_ON_DELIVERY))
                .post("/invoices");

        // Then it is unauthorised - an invoice belongs to a customer
        assertThatStatus(response).isEqualTo(401);
    }

    /**
     * The application cross-checks the billing city against its own postcode
     * lookup, so an address edited after the auto-fill no longer matches.
     */
    @Test
    @Regression
    @Story("A billing city that does not belong to the country is rejected")
    void aBillingCityThatDoesNotBelongToTheCountryIsRejected() {
        // Given an otherwise valid invoice whose city has been replaced
        String cartId = api.cartContaining(api.someProductInStock(), 1);
        InvoiceRequest tampered = new InvoiceRequest("Test Street 1", "Nowhereville", "Nowhere",
                "NL", "1011AB", PaymentMethod.CASH_ON_DELIVERY, cartId, Map.of());

        // When it is submitted
        Response response = api.as(config.customer()).body(tampered).post("/invoices");

        // Then it is refused, naming the field that disagrees
        assertThatStatus(response).isEqualTo(422);
        assertThat(response.asString()).contains("billing_country");
    }

    @Test
    @Regression
    @Story("A payment method outside the advertised set is rejected")
    void aPaymentMethodOutsideTheAdvertisedSetIsRejected() {
        // Given a valid invoice with a payment method the API does not offer.
        // The enum makes this unreachable from ordinary test code, which is the
        // point of the enum - so this one sends a raw map on purpose.
        String cartId = api.cartContaining(api.someProductInStock(), 1);
        PostcodeLookup address = api.addressFor("NL", "1011AB");
        Map<String, Object> body = Map.of(
                "billing_street", "Test Street 1",
                "billing_city", address.city(),
                "billing_state", address.state(),
                "billing_country", address.country(),
                "billing_postal_code", address.postcode(),
                "payment_method", "bitcoin",
                "cart_id", cartId,
                "payment_details", Map.of());

        // When it is submitted
        Response response = api.as(config.customer()).body(body).post("/invoices");

        // Then it is refused
        assertThatStatus(response).isEqualTo(422);
        assertThat(response.asString()).contains("payment_method");
    }

    /**
     * Gift card details are the only payment details this endpoint validates -
     * 16 alphanumeric characters, and a 4-character code. Everything else is
     * checked by {@code POST /payment/check} instead.
     */
    @Test
    @Regression
    @Story("A gift card number of the wrong format is rejected")
    void aGiftCardNumberOfTheWrongFormatIsRejected() {
        String cartId = api.cartContaining(api.someProductInStock(), 1);
        InvoiceRequest request = validInvoiceFor(cartId, PaymentMethod.GIFT_CARD)
                .payingWith(PaymentMethod.GIFT_CARD,
                        Map.of("gift_card_number", "too-short", "validation_code", "1234"));

        Response response = api.as(config.customer()).body(request).post("/invoices");

        assertThatStatus(response).isEqualTo(422);
        assertThat(response.asString()).contains("gift_card_number");
    }

    /** A billing address the application accepts, taken from its own lookup. */
    private InvoiceRequest validInvoiceFor(String cartId, PaymentMethod method) {
        PostcodeLookup address = api.addressFor("NL", "1011AB");
        return new InvoiceRequest("Test Street 1", address.city(), address.state(),
                address.country(), address.postcode(), method, cartId,
                ToolshopApi.paymentDetailsFor(method));
    }
}
