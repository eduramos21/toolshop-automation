package toolshop.automation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import toolshop.automation.api.payload.LoginRequest;
import toolshop.automation.api.payload.PaymentMethod;
import toolshop.automation.api.payload.PostcodeLookup;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.tags.Contract;
import toolshop.automation.core.testdata.TestDataRegistry;

/**
 * Where the application's documentation and the application disagree.
 *
 * <p>Every API call in this suite is already validated against the OpenAPI
 * document - the validator is a filter on the shared client, so it is not
 * opt-in and a new test gets it without asking. Known disagreements are
 * whitelisted entry by entry in {@link ContractSpec} so that a <em>new</em> one
 * fails whichever test hits it.
 *
 * <p>These tests guard the whitelist itself. Each one replays an interaction
 * against a validator with no whitelist and asserts the deviation is still
 * there. So when the application documents one of these properly, the test
 * fails and says the whitelist entry can go - which is the difference between a
 * whitelist and a graveyard.
 *
 * <p>Every deviation here is the document under-reporting what the application
 * actually answers. None of them is a case of the application returning
 * something the document forbids, which is the more alarming direction and does
 * not occur.
 */
@Contract
@Epic("API contract")
@Feature("OpenAPI conformance")
class ApiContractTest {

    private final ToolshopApi api;
    private final ToolshopConfig config;

    ApiContractTest(ToolshopApi api, ToolshopConfig config) {
        this.api = api;
        this.config = config;
    }

    /**
     * The document is OpenAPI 3.2 and no Java validator supports 3.2 yet, so it
     * is normalised to 3.1 with the {@code query} operations removed. This test
     * pins what that costs: if the application stops using {@code QUERY}, or a
     * parser learns 3.2, the list changes and the normalising can be revisited.
     */
    @Test
    @Story("The document loads, and what had to be dropped to load it is known")
    @Severity(SeverityLevel.NORMAL)
    void theDocumentLoadsAndNamesWhatWasDroppedToLoadIt() {
        assertThat(api.contract().validator()).isNotNull();
        assertThat(api.contract().removedOperations())
                .as("OpenAPI 3.2 QUERY operations, which swagger-parser cannot read")
                .containsExactlyInAnyOrder(
                        "QUERY /brands/search",
                        "QUERY /categories/tree",
                        "QUERY /categories/search",
                        "QUERY /invoices/search",
                        "QUERY /products",
                        "QUERY /products/search",
                        "QUERY /users/search");
    }

    @Test
    @Story("A rejected login answers a status the document does not list")
    @Severity(SeverityLevel.CRITICAL)
    void aRejectedLoginAnswersAnUndocumentedStatus(TestDataRegistry data) {
        ToolshopApi.DisposableCustomer throwaway = api.createDisposableCustomer(data);

        assertThatThrownBy(() -> api.strictlyValidated()
                .body(new LoginRequest(throwaway.credentials().email(), "not-the-password"))
                .post("/users/login"))
                .hasMessageContaining("Response status 401 not defined for path '/users/login'");
    }

    @Test
    @Story("A locked account answers a status the document does not list")
    void aLockedAccountAnswersAnUndocumentedStatus(TestDataRegistry data) {
        // Given an account locked by three failed attempts
        ToolshopApi.DisposableCustomer throwaway = api.createDisposableCustomer(data);
        for (int attempt = 1; attempt <= 3; attempt++) {
            api.anonymous()
                    .body(new LoginRequest(throwaway.credentials().email(), "wrong-" + attempt))
                    .post("/users/login");
        }

        // Then 423 comes back, and the document has never heard of it
        assertThatThrownBy(() -> api.strictlyValidated()
                .body(new LoginRequest(throwaway.credentials().email(),
                        throwaway.credentials().password()))
                .post("/users/login"))
                .hasMessageContaining("Response status 423 not defined for path '/users/login'");
    }

    @Test
    @Story("A raised invoice answers a status the document does not list")
    @Severity(SeverityLevel.CRITICAL)
    void aRaisedInvoiceAnswersAnUndocumentedStatus() {
        // Given a cart ready to be checked out
        PostcodeLookup address = api.addressFor("NL", "1011AB");
        String cartId = api.cartContaining(api.anyProductInStock().id(), 1);

        // Then the 201 it answers with is not in the document, which lists 200
        assertThatThrownBy(() -> api.strictlyValidated()
                .header("Authorization", "Bearer " + tokenFor())
                .body(Map.of(
                        "billing_street", "Test Street 1",
                        "billing_city", address.city(),
                        "billing_state", address.state(),
                        "billing_country", address.country(),
                        "billing_postal_code", address.postcode(),
                        "payment_method", PaymentMethod.CASH_ON_DELIVERY.wireName(),
                        "cart_id", cartId,
                        "payment_details", Map.of()))
                .post("/invoices"))
                .hasMessageContaining("Response status 201 not defined for path '/invoices'");
    }

    /**
     * The most interesting of the set. The document lists 401 for these routes
     * but not 403, and those are answers to different questions - "I do not know
     * who you are" against "I know exactly who you are and no". A client written
     * from the document would have no reason to handle the second.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/users", "/reports/total-sales-of-years", "/reports/customers-by-country"})
    @Story("An administrative route answers 403, which the document does not list")
    @Severity(SeverityLevel.CRITICAL)
    void anAdministrativeRouteAnswersAnUndocumentedForbidden(String path) {
        assertThatThrownBy(() -> api.strictlyValidated()
                .header("Authorization", "Bearer " + tokenFor())
                .get(path))
                .hasMessageContaining("Response status 403 not defined for path '" + path + "'");
    }

    @Test
    @Story("A rejected registration answers a status the document does not list")
    void aRejectedRegistrationAnswersAnUndocumentedStatus() {
        assertThatThrownBy(() -> api.strictlyValidated()
                .body(Map.of("email", "nobody@example.test"))
                .post("/users/register"))
                .hasMessageContaining("Response status 422 not defined for path '/users/register'");
    }

    /** A token for the seeded customer, taken through the ordinary client. */
    private String tokenFor() {
        return api.anonymous()
                .body(new LoginRequest(config.customer().email(), config.customer().password()))
                .post("/users/login")
                .jsonPath().getString("access_token");
    }
}
