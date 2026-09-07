package toolshop.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.Map;
import toolshop.automation.api.payload.PaymentMethod;

/** Checkout, step four: pay, and finish. */
public final class PaymentStep {

    private final Page page;

    PaymentStep(Page page) {
        this.page = page;
    }

    /**
     * The select's option values are the same strings the API accepts, so
     * {@link PaymentMethod} drives both layers and neither can drift from the
     * other without the other noticing.
     */
    public PaymentStep choose(PaymentMethod method) {
        page.getByTestId("payment-method").selectOption(method.wireName());
        return this;
    }

    /**
     * Fills the payment detail fields from the same map the API client sends.
     *
     * <p>The application uses the identical names on both sides - the JSON keys
     * of {@code payment_details} and the {@code data-test} attributes of these
     * inputs are the same strings - so one definition of "valid details for this
     * method" serves the API test and the UI test. Worth using rather than
     * writing the values out twice and letting them disagree.
     */
    public PaymentStep enterDetails(Map<String, Object> details) {
        details.forEach((field, value) -> set(field, String.valueOf(value)));
        return this;
    }

    /**
     * The result of the payment pre-check, which is <em>not</em> the order
     * confirmation. Asserting on this and stopping is how a suite reports a
     * successful checkout that never created an order - see
     * {@link #submitOrder()}.
     */
    public Locator paymentCheckMessage() {
        return page.getByTestId("payment-success-message");
    }

    public Locator errorMessage() {
        return page.getByTestId("payment-error-message");
    }

    /**
     * The order confirmation.
     *
     * <p>Located by {@code id} rather than by test id, because this block is the
     * one thing in the checkout that carries no {@code data-test} attribute. An
     * id is the most stable locator available here and is preferred to XPath;
     * it is noted rather than hidden.
     */
    public Locator orderConfirmation() {
        return page.locator("#order-confirmation");
    }

    /** One click on finish, which is what a correct application would need. */
    public PaymentStep finish() {
        page.getByTestId("finish").click();
        return this;
    }

    /**
     * Places the order, which takes two clicks on this application.
     *
     * <p><b>This works around a defect in the application under test, and the
     * defect is reported by a test rather than absorbed here.</b>
     * {@code PaymentStep.finishFunction} calls {@code checkPayment(...)}, which
     * fires its HTTP request and then immediately returns
     * {@code of(this.state)} - the value of the field <em>before</em> that
     * request resolves. On the first click {@code state} is {@code undefined},
     * so the {@code result === true} guard fails and {@code POST /invoices} is
     * never sent. The request then completes and sets {@code state = true}, so a
     * second click places the order.
     *
     * <p>The user-visible effect is worse than the mechanism: the payment
     * message appears on the first click, so the page reports success while no
     * order exists. The invoice call's error handler is also empty, so a genuine
     * failure would look identical.
     *
     * <p>{@code CheckoutDefectUiTest} asserts the correct single-click behaviour
     * and is quarantined. This method exists so the rest of the slice - invoice
     * row, invoice line, confirmation email - can still be verified today.
     */
    public PaymentStep submitOrder() {
        finish();
        // Waiting on the pre-check message rather than on a duration: it is the
        // observable signal that `state` has been set, which is the actual
        // precondition for the second click doing anything.
        paymentCheckMessage().waitFor();
        finish();
        return this;
    }

    /** The invoice number the confirmation reports, e.g. {@code INV-2026000032}. */
    public String invoiceNumber() {
        String text = orderConfirmation().innerText();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("INV-\\d+").matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("no invoice number in the confirmation: " + text);
        }
        return matcher.group();
    }

    /**
     * Some detail fields are selects rather than inputs - the instalment count,
     * for one - so the element decides how it is filled instead of the caller
     * keeping a list of which is which.
     */
    private void set(String testId, String value) {
        Locator field = page.getByTestId(testId);
        if ("SELECT".equals(field.evaluate("element => element.tagName"))) {
            field.selectOption(value);
        } else {
            field.fill(value);
        }
    }
}
