package toolshop.automation.ui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.junit.UsePlaywright;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import toolshop.automation.api.ToolshopApi;
import toolshop.automation.api.payload.PaymentMethod;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.tags.Checkout;
import toolshop.automation.core.tags.Db;
import toolshop.automation.core.tags.Quarantine;
import toolshop.automation.core.tags.Regression;
import toolshop.automation.core.tags.Ui;
import toolshop.automation.core.testdata.TestDataRegistry;
import toolshop.automation.ui.pages.AddressStep;
import toolshop.automation.ui.pages.PaymentStep;
import toolshop.automation.ui.pages.ProductPage;
import toolshop.automation.ui.pages.StorefrontPage;

/**
 * A defect in the application under test, reported as a failing test.
 *
 * <p>Pressing "finish" once shows the payment message and places no order. The
 * cause is in {@code payment.component.ts}: {@code checkPayment()} fires its
 * request and then returns {@code of(this.state)} - the field's value
 * <em>before</em> the response arrives. On the first click that is
 * {@code undefined}, the {@code result === true} guard fails, and
 * {@code POST /invoices} is never sent. The response then sets
 * {@code state = true}, so a second click works.
 *
 * <p>Measured: with one click, no {@code POST /invoices} appears in the browser's
 * network log and no invoice row is written. With two, the request is sent and
 * answers 201.
 *
 * <p>What makes it worth a test of its own is the user-visible half. The payment
 * message appears on the first click, so the page reports success while nothing
 * was ordered - and the invoice call's error handler is empty
 * ({@code error: () => {}}), so a real failure would look exactly the same. A
 * suite that asserted on the payment message would report this as passing.
 *
 * <h2>Why quarantined rather than deleted or "fixed" in the page object</h2>
 *
 * <p>The application is third-party here, so this cannot be fixed in this
 * repository. The options were to assert the broken behaviour - which turns a
 * defect into a requirement and means the suite goes red when it is fixed - or
 * to say what should happen and mark it known. {@code @Quarantine} is that mark:
 * excluded from the blocking run, kept visible, run on a schedule so its status
 * is observable. It is not a retry, and nothing about it is hidden.
 *
 * <p>{@code CheckoutUiTest} still verifies the rest of the slice - invoice row,
 * invoice line, confirmation email - by driving the application the way it
 * actually behaves, in exactly one documented place.
 */
@Ui
@Checkout
@Db
@Regression
@Quarantine
@Epic("Checkout")
@Feature("Storefront checkout")
@UsePlaywright(ToolshopOptions.class)
class CheckoutDefectUiTest {

    private final ToolshopApi api;
    private final ToolshopConfig config;

    CheckoutDefectUiTest(ToolshopApi api, ToolshopConfig config) {
        this.api = api;
        this.config = config;
    }

    @Test
    @Story("One press of finish places the order")
    @Severity(SeverityLevel.CRITICAL)
    void onePressOfFinishPlacesTheOrder(Page page, TestDataRegistry data) {
        // Given a customer with something in the cart, at a valid address
        ToolshopApi.DisposableCustomer buyer = Buyers.create(api, config, data);
        ToolshopApi.Product product = api.anyProductInStock();

        ProductPage productPage = new StorefrontPage(page).open().openProduct(product.id());
        productPage.chooseQuantity(1).addToCart();

        AddressStep address = productPage.openCart().proceed()
                .signIn(buyer.credentials().email(), buyer.credentials().password())
                .proceed();
        address.enterAddressKey("NL", "1011AB", "1");

        // When finish is pressed once
        PaymentStep payment = address.proceed();
        payment.choose(PaymentMethod.CREDIT_CARD)
                .enterDetails(ToolshopApi.paymentDetailsFor(PaymentMethod.CREDIT_CARD))
                .finish();

        // Then the order is placed. It is not: the payment message appears and no
        // invoice is created, so this fails until the application is fixed.
        assertThat(payment.orderConfirmation()).isVisible();
    }
}
