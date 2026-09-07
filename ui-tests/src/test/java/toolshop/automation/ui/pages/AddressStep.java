package toolshop.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Checkout, step three: the billing address.
 *
 * <p>The interesting part is what this page object does <em>not</em> contain. On
 * entering a country, postcode and house number the application performs a
 * debounced lookup and fills the street, city and state itself; the proceed
 * button stays disabled until the form is valid. So {@link #proceed()} is a
 * single click with no waiting code at all - Playwright's {@code click} waits for
 * the element to be enabled, which is the same condition as "the lookup came
 * back and filled the form".
 *
 * <p>That is the whole argument against explicit sleeps in one method: the sleep
 * would encode a guess about the debounce interval, and this encodes the actual
 * condition.
 */
public final class AddressStep {

    private final Page page;

    AddressStep(Page page) {
        this.page = page;
    }

    /**
     * Enters the three fields the lookup keys on, and waits for the lookup that
     * they trigger.
     *
     * <p>An explicit response wait, not a sleep and not a wait on the rendered
     * fields. The application debounces each of these controls by 300ms and then
     * patches street, city and state from the response, so the condition that
     * actually matters is "the lookup answered". Waiting on the button becoming
     * enabled looks equivalent and is not: three debounced controls can produce
     * more than one request, and the button can be observed enabled between
     * them.
     *
     * <p>The country select carries ISO-2 codes as its option values, e.g.
     * {@code NL}.
     */
    public AddressStep enterAddressKey(String isoCountryCode, String postcode, String houseNumber) {
        // Any answer, not only a 200. A predicate that insists on success turns
        // a rejected lookup into a 30-second timeout that says nothing; letting
        // the failure through means the assertions below report what the
        // application actually said.
        com.microsoft.playwright.Response lookup = page.waitForResponse(
                response -> response.url().contains("/postcode-lookup"),
                () -> {
                    // Country LAST, and that ordering is load-bearing.
                    //
                    // The address form echoes every change up to the checkout
                    // component and receives it back as a patch. Playwright's
                    // fill is select-all-then-insert, so a patch landing between
                    // those two steps drops the selection and the text is
                    // appended instead of replacing: the postcode arrives at the
                    // API as "1011AB1011AB" and the lookup answers 422.
                    //
                    // Filling the text fields first lets each echo settle, and
                    // the country select - which is the only one of the three
                    // that triggers the lookup without a debounce - then fires
                    // exactly one request with all three values already in place.
                    page.getByTestId("postal_code").fill(postcode);
                    page.getByTestId("house_number").fill(houseNumber);
                    page.getByTestId("country").selectOption(isoCountryCode);
                });

        if (lookup.status() != 200) {
            throw new AssertionError("the address lookup was rejected, so the form cannot become"
                    + " valid.\n  " + lookup.request().method() + " " + lookup.url()
                    + "\n  -> " + lookup.status() + " " + lookup.text());
        }
        return this;
    }

    public Locator street() {
        return page.getByTestId("street");
    }

    public Locator city() {
        return page.getByTestId("city");
    }

    public Locator state() {
        return page.getByTestId("state");
    }

    public Locator lookupError() {
        return page.getByTestId("postcode-lookup-error");
    }

    public PaymentStep proceed() {
        page.getByTestId("proceed-3").click();
        return new PaymentStep(page);
    }
}
