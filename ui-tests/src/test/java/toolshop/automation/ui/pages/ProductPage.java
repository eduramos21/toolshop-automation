package toolshop.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** A single product, and the only place a cart gets filled from. */
public final class ProductPage {

    private final Page page;

    ProductPage(Page page) {
        this.page = page;
    }

    public Locator name() {
        return page.getByTestId("product-name");
    }

    public Locator unitPrice() {
        return page.getByTestId("unit-price");
    }

    public Locator cartBadge() {
        return page.getByTestId("cart-quantity");
    }

    public ProductPage chooseQuantity(int quantity) {
        page.getByTestId("quantity").fill(String.valueOf(quantity));
        return this;
    }

    public ProductPage addToCart() {
        page.getByTestId("add-to-cart").click();
        return this;
    }

    public CartStep openCart() {
        page.getByTestId("nav-cart").click();
        return new CartStep(page);
    }
}
