package toolshop.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * The product overview: the landing page, search, and the grid.
 *
 * <p>Page objects here expose {@link Locator}s and actions, never assertions. An
 * object that asserts cannot be reused by a test expecting a different outcome,
 * and the negative cases are half of what is worth testing.
 */
public final class StorefrontPage {

    private final Page page;

    public StorefrontPage(Page page) {
        this.page = page;
    }

    public StorefrontPage open() {
        page.navigate("/");
        return this;
    }

    /** The card for one product. The application renders {@code data-test="product-<id>"}. */
    public Locator card(String productId) {
        return page.getByTestId("product-" + productId);
    }

    public Locator searchBox() {
        return page.getByTestId("search-query");
    }

    public Locator resultCount() {
        return page.getByTestId("search-result-count");
    }

    public Locator noResults() {
        return page.getByTestId("no-results");
    }

    public Locator productNames() {
        return page.getByTestId("product-name");
    }

    public Locator outOfStockMarkers() {
        return page.getByTestId("out-of-stock");
    }

    public Locator cartBadge() {
        return page.getByTestId("cart-quantity");
    }

    public StorefrontPage search(String term) {
        searchBox().fill(term);
        page.getByTestId("search-submit").click();
        return this;
    }

    public ProductPage openProduct(String productId) {
        card(productId).click();
        return new ProductPage(page);
    }
}
