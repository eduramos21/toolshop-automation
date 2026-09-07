package toolshop.automation.ui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.junit.UsePlaywright;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import toolshop.automation.api.ToolshopApi;
import toolshop.automation.api.payload.PaymentMethod;
import toolshop.automation.api.payload.PostcodeLookup;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.tags.Checkout;
import toolshop.automation.core.tags.Db;
import toolshop.automation.core.tags.Regression;
import toolshop.automation.core.tags.Smoke;
import toolshop.automation.core.tags.Ui;
import toolshop.automation.data.InvoiceItemRow;
import toolshop.automation.data.InvoiceRow;
import toolshop.automation.data.MailCatcher;
import toolshop.automation.data.MailMessage;
import toolshop.automation.data.ToolshopDatabase;
import toolshop.automation.core.testdata.TestDataRegistry;
import toolshop.automation.ui.pages.AddressStep;
import toolshop.automation.ui.pages.CartStep;
import toolshop.automation.ui.pages.PaymentStep;
import toolshop.automation.ui.pages.ProductPage;
import toolshop.automation.ui.pages.StorefrontPage;

/**
 * The checkout slice, end to end: storefront, API-created account, browser
 * checkout, invoice row, confirmation email.
 *
 * <p>The buyer is created through the API and removed by the test-data registry,
 * so this test never depends on a seeded account whose state something else
 * could have changed - and because the address is unique to this test, both the
 * invoice row and the confirmation email can be identified exactly rather than
 * by guessing which of several belongs to it.
 *
 * <p>Every test here carries {@code @Db}, and not only the one that asserts on
 * the database. A completed order cannot be undone through the API - deleting a
 * customer who has an invoice answers 409, and there is no endpoint that removes
 * an invoice - so a checkout test can only clean up after itself where the
 * database is reachable. On a target without one, exclude them:
 * {@code ./run test -Ptags='!db'}.
 *
 * <p>Playwright's assertions are used for page state and AssertJ for values.
 * That split is not cosmetic: AssertJ on {@code textContent()} is a snapshot of
 * a page that may still be rendering, and the only way to make it reliable is a
 * sleep. Playwright's retry until the condition holds, so what gets written down
 * is the condition rather than a guess at the timing.
 */
@Ui
@Checkout
@Epic("Checkout")
@Feature("Storefront checkout")
@UsePlaywright(ToolshopOptions.class)
class CheckoutUiTest {

    private static final String COUNTRY = "NL";
    private static final String POSTCODE = "1011AB";
    private static final String HOUSE_NUMBER = "1";
    private static final Duration MAIL_BUDGET = Duration.ofSeconds(60);

    private final ToolshopApi api;
    private final ToolshopConfig config;

    CheckoutUiTest(ToolshopApi api, ToolshopConfig config) {
        this.api = api;
        this.config = config;
    }

    /**
     * The whole point of the framework in one test: the same purchase followed
     * through four layers, each of which can disagree with the others.
     *
     * <p>A success message on a page is not evidence that an order exists. A
     * suite that stops there passes while the invoice is missing, the line is
     * wrong, or the customer was never told.
     */
    @Test
    @Smoke
    @Db
    @Story("A purchase reaches the invoice table and the customer's inbox")
    @Severity(SeverityLevel.BLOCKER)
    void aPurchaseIsRecordedAndConfirmed(Page page, TestDataRegistry data) {
        // Given a customer who exists only for this test, and a product in stock
        ToolshopApi.DisposableCustomer buyer = Buyers.create(api, config, data);
        ToolshopApi.Product product = api.anyProductInStock();
        BigDecimal expectedTotal = BigDecimal.valueOf(product.price()).multiply(BigDecimal.valueOf(2));

        // When they buy two of it through the storefront and pay by card
        String invoiceNumber = checkOut(page, buyer, product, 2, PaymentMethod.CREDIT_CARD);

        // Then the invoice the storefront reported actually exists.
        //
        // Looked up by the number the confirmation showed, rather than by "the
        // newest invoice for this customer". Both are unambiguous here, but this
        // one also checks that the number the customer was given is the number
        // that was written - which is the part a customer would quote back.
        ToolshopDatabase database = ToolshopDatabase.from(config);
        InvoiceRow invoice = database.invoiceNumbered(invoiceNumber)
                .orElseThrow(() -> new AssertionError(
                        "the storefront reported invoice " + invoiceNumber
                        + " but no such row exists"));

        Assertions.assertThat(invoiceNumber).startsWith("INV-");
        Assertions.assertThat(invoice.total())
                .as("two of %s at %s", product.name(), product.price())
                .isEqualByComparingTo(expectedTotal);
        Assertions.assertThat(invoice.billingCountry()).isEqualTo(COUNTRY);

        // And it has exactly the line that was bought
        List<InvoiceItemRow> items = database.itemsOf(invoice.id());
        Assertions.assertThat(items).singleElement().satisfies(item -> {
            Assertions.assertThat(item.productId()).isEqualTo(product.id());
            Assertions.assertThat(item.quantity()).isEqualTo(2);
            Assertions.assertThat(item.unitPrice())
                    .isEqualByComparingTo(BigDecimal.valueOf(product.price()));
        });

        // And the customer was told
        MailCatcher mail = MailCatcher.from(config);
        MailMessage confirmation =
                mail.awaitMessage(buyer.credentials().email(), "Checkout", MAIL_BUDGET);

        Assertions.assertThat(mail.plainBodyOf(confirmation))
                .contains(product.name())
                .contains(money(product.price() * 2));
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    @Regression
    @Db
    @Story("Every payment method the storefront offers completes a checkout")
    @Severity(SeverityLevel.CRITICAL)
    void everyPaymentMethodCompletesACheckout(PaymentMethod method, Page page, TestDataRegistry data) {
        // Given a customer per case, so the cases cannot interfere
        ToolshopApi.DisposableCustomer buyer = Buyers.create(api, config, data);
        ToolshopApi.Product product = api.anyProductInStock();

        // When the cart is paid for this way
        checkOut(page, buyer, product, 1, method);
    }

    /**
     * The whole flow, with the invariants of each step asserted where they hold.
     *
     * <p>One flow rather than one per test: the steps are the same regardless of
     * payment method, and writing them out twice is how two versions of a
     * checkout end up disagreeing about what the third step does.
     */
    private String checkOut(Page page, ToolshopApi.DisposableCustomer buyer,
            ToolshopApi.Product product, int quantity, PaymentMethod method) {

        PostcodeLookup expectedAddress = api.addressFor(COUNTRY, POSTCODE);

        ProductPage productPage = new StorefrontPage(page).open().openProduct(product.id());
        assertThat(productPage.name()).hasText(product.name());

        productPage.chooseQuantity(quantity).addToCart();
        assertThat(productPage.cartBadge()).hasText(String.valueOf(quantity));

        CartStep cart = productPage.openCart();
        assertThat(cart.productTitles()).hasCount(1);
        assertThat(cart.productTitles()).hasText(product.name());
        assertThat(cart.total()).containsText(money(product.price() * quantity));

        AddressStep address = cart.proceed()
                .signIn(buyer.credentials().email(), buyer.credentials().password())
                .proceed();

        address.enterAddressKey(COUNTRY, POSTCODE, HOUSE_NUMBER);

        // The application filled these from its own lookup, which is what makes
        // the form valid and the proceed button usable.
        assertThat(address.lookupError()).not().isVisible();
        assertThat(address.street()).not().isEmpty();
        assertThat(address.city()).hasValue(expectedAddress.city());
        assertThat(address.state()).hasValue(expectedAddress.state());

        PaymentStep payment = address.proceed();
        payment.choose(method)
                .enterDetails(ToolshopApi.paymentDetailsFor(method))
                .submitOrder();

        assertThat(payment.errorMessage()).not().isVisible();

        // The order confirmation, not the payment message. The payment message
        // appears whether or not an order was created - see
        // PaymentStep.submitOrder for the defect that makes the difference.
        assertThat(payment.orderConfirmation()).isVisible();
        return payment.invoiceNumber();
    }

    /** Two decimal places, the way the storefront and the email render a price. */
    private static String money(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }
}
