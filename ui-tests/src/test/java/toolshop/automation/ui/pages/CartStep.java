package toolshop.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Checkout, step one.
 *
 * <p>The whole checkout is a single route with inline steps, so each step is a
 * page object over the same {@link Page} rather than a separate URL.
 */
public final class CartStep {

    private final Page page;

    CartStep(Page page) {
        this.page = page;
    }

    public Locator productTitles() {
        return page.getByTestId("product-title");
    }

    public Locator quantities() {
        return page.getByTestId("product-quantity");
    }

    public Locator linePrices() {
        return page.getByTestId("line-price");
    }

    public Locator total() {
        return page.getByTestId("cart-total");
    }

    public SignInStep proceed() {
        page.getByTestId("proceed-1").click();
        return new SignInStep(page);
    }
}
