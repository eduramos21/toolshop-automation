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
import toolshop.automation.core.tags.Smoke;
import toolshop.automation.core.tags.Storefront;
import toolshop.automation.core.tags.Ui;
import toolshop.automation.ui.pages.StorefrontPage;

/**
 * The storefront: listing, search, and the cart badge.
 *
 * <p>Every assertion here is a Playwright web assertion, and that is a
 * deliberate exception to AssertJ being the only assertion vocabulary. AssertJ
 * on {@code locator.textContent()} is a snapshot of a page that is still
 * rendering, and making it reliable would need a sleep - which the brief forbids
 * and which would be a guess about the application's timing. Playwright's
 * assertions retry until the condition holds or the timeout expires, so the
 * condition is what gets written down. Values that are not page state are still
 * AssertJ's.
 */
@Ui
@Storefront
@Epic("Storefront")
@Feature("Browsing and search")
@UsePlaywright(ToolshopOptions.class)
class StorefrontUiTest {

    private final ToolshopApi api;

    StorefrontUiTest(ToolshopApi api) {
        this.api = api;
    }

    @Test
    @Smoke
    @Story("The storefront lists products")
    @Severity(SeverityLevel.BLOCKER)
    void theStorefrontListsProducts(Page page) {
        // Given a product the application says is in stock
        ToolshopApi.Product product = api.anyProductInStock();

        // When the storefront is opened
        StorefrontPage storefront = new StorefrontPage(page).open();

        // Then that product is on it
        assertThat(storefront.card(product.id())).isVisible();
        assertThat(storefront.productNames().first()).not().isEmpty();
    }

    /**
     * The term comes from the application's own search, not from a guess. Its
     * search is a boolean fulltext match that requires every word, so a
     * product's own full name returns nothing - "with" is a MySQL stopword. That
     * is the search's business; this test is about whether the storefront
     * renders what the search returned.
     */
    @Test
    @Story("The storefront shows what a search matched")
    void theStorefrontShowsWhatASearchMatched(Page page) {
        // Given a term the application's search matches, and what it matches
        ToolshopApi.Search expected = api.aSearchThatMatches();

        // When that term is searched for in the storefront
        StorefrontPage storefront = new StorefrontPage(page).open().search(expected.term());

        // Then the term is echoed back and the matches are on the page
        assertThat(page.getByTestId("search-term")).hasText(expected.term());
        assertThat(storefront.resultCount()).isVisible();
        assertThat(storefront.card(expected.productIds().get(0))).isVisible();
    }

    @Test
    @Story("A search with no matches says so rather than showing nothing")
    void aSearchWithNoMatchesSaysSo(Page page) {
        // Given a term no product can match
        // When it is searched for
        StorefrontPage storefront = new StorefrontPage(page).open()
                .search("no-such-tool-zzzzz");

        // Then the application says so. An empty grid and a broken grid look the
        // same to a user, so the message is the assertion worth making.
        assertThat(storefront.noResults()).isVisible();
    }

    @Test
    @Smoke
    @Story("Adding to the cart updates the header badge")
    @Severity(SeverityLevel.CRITICAL)
    void addingToTheCartUpdatesTheHeaderBadge(Page page) {
        // Given a product in stock
        ToolshopApi.Product product = api.anyProductInStock();

        // When three of it are added to the cart
        new StorefrontPage(page).open()
                .openProduct(product.id())
                .chooseQuantity(3)
                .addToCart();

        // Then the header says three. No wait is written here: the assertion
        // retries until the badge holds the value, which is the actual condition.
        assertThat(page.getByTestId("cart-quantity")).hasText("3");
    }
}
