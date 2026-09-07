package toolshop.automation.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.junit.UsePlaywright;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import toolshop.automation.api.ToolshopApi;
import toolshop.automation.core.tags.A11y;
import toolshop.automation.core.tags.Regression;

/**
 * Accessibility of the pages a customer has to get through to buy something.
 *
 * <p>Its own suite, so a violation does not block a functional run. The two
 * kinds of failure need different readers and different fixes, and mixing them
 * means the louder one buries the other.
 *
 * <h2>Asserted against a baseline, not against zero</h2>
 *
 * <p>Asserting zero violations would mean this suite was red from the day it was
 * written and stayed red, which is the same as not having it. Asserting
 * "no more than before" would let a fix go unnoticed and let a regression hide
 * behind an unrelated fix.
 *
 * <p>So each page declares exactly the rules it currently violates. A new
 * violation fails. A fixed violation also fails, saying the baseline is stale.
 * Same shape as the OpenAPI whitelist in {@code ContractSpec}, for the same
 * reason: a list of known problems is only useful while something forces it to
 * stay accurate.
 *
 * <p>Scanned against WCAG 2.0 A and AA. Every violation is attached to the
 * report with its affected nodes, so a failure is actionable rather than a
 * rule id.
 */
@A11y
@Regression
@Epic("Accessibility")
@Feature("WCAG 2.0 A and AA")
@UsePlaywright(ToolshopOptions.class)
class AccessibilityUiTest {

    private static final List<String> STANDARD = List.of("wcag2a", "wcag2aa");

    private final ToolshopApi api;

    AccessibilityUiTest(ToolshopApi api) {
        this.api = api;
    }

    /**
     * The known violations, measured 2026-09.
     *
     * <ul>
     *   <li>{@code list} - a {@code <ul>} containing something other than
     *       {@code <li>}. Serious, and on the storefront it affects three nodes.
     *   <li>{@code button-name} - a button with no accessible name, on both
     *       authentication forms. <b>Critical</b>: a screen reader announces it
     *       as "button" and nothing else, on the two pages where someone signs
     *       in or creates an account.
     * </ul>
     *
     * <p>The sign-in page's violation is only visible if the scan waits for the
     * page to finish loading. A first pass without that wait reported zero, and
     * an accessibility scan that under-reports is worse than one that does not
     * run, because it produces a green tick.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/,               list",
            "/auth/login,     button-name",
            "/auth/register,  button-name|list",
            "/contact,        ",
    })
    @Story("A page has only the accessibility violations already known about")
    @Severity(SeverityLevel.NORMAL)
    void aPageHasOnlyTheViolationsAlreadyKnownAbout(String path, String knownRules, Page page) {
        // Given the page, and the rules it is known to violate
        Set<String> known = knownRules == null || knownRules.isBlank()
                ? Set.of()
                : Set.of(knownRules.split("\\|"));

        // When it is scanned
        page.navigate(path);
        Set<String> found = scan(page, path);

        // Then nothing has appeared and nothing has silently been fixed
        assertThat(found)
                .as("accessibility violations on %s; a new one is a regression, "
                        + "a missing one means this baseline is stale", path)
                .isEqualTo(known);
    }

    /**
     * The product page is scanned separately because its URL needs a real
     * product, which a {@code @CsvSource} cannot supply.
     */
    @Test
    @Story("The product page has no accessibility violations")
    void theProductPageHasNoViolations(Page page) {
        ToolshopApi.Product product = api.anyProductInStock();

        page.navigate("/product/" + product.id());
        Set<String> found = scan(page, "/product/" + product.id());

        assertThat(found).isEmpty();
    }

    /**
     * Runs axe in the page and attaches what it found.
     *
     * <p>Returns rule ids rather than the report, because that is what the
     * baseline is expressed in - but the detail goes to the report, so a failure
     * names the elements rather than leaving a rule id to look up.
     */
    private static Set<String> scan(Page page, String label) {
        // A whole-page scan has to wait for the whole page. Without this the
        // result depends on what had rendered by the time axe ran: the sign-in
        // page reported a nameless button on some runs and not others, because a
        // lazily-rendered control had not yet been given its label.
        //
        // NETWORKIDLE rather than a duration. Playwright discourages it for
        // waiting on a specific element - there is almost always a better
        // condition for that - but "the page has finished loading everything" is
        // precisely the precondition here, and it is a condition rather than a
        // guess at how long one takes.
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);

        AxeResults results = new AxeBuilder(page).withTags(STANDARD).analyze();

        if (!results.getViolations().isEmpty()) {
            Allure.addAttachment("Accessibility violations on " + label, "text/plain",
                    results.getViolations().stream()
                            .map(AccessibilityUiTest::describe)
                            .collect(Collectors.joining("\n\n")));
        }

        return results.getViolations().stream().map(Rule::getId).collect(Collectors.toSet());
    }

    private static String describe(Rule rule) {
        return rule.getImpact().toUpperCase(java.util.Locale.ROOT) + "  " + rule.getId()
                + "\n  " + rule.getHelp()
                + "\n  " + rule.getHelpUrl()
                + rule.getNodes().stream()
                        .map(node -> "\n    " + node.getHtml())
                        .collect(Collectors.joining());
    }
}
