package toolshop.automation.core.tags;

/**
 * The tag vocabulary, as constants.
 *
 * <p>Tests never reference this class. They apply the composed annotations in
 * this package instead - {@code @Smoke}, not {@code @Tag(Tags.SMOKE)} and
 * certainly not {@code @Tag("smoke")}. The constants exist so that each
 * annotation and the string it carries are declared in one place, and so that
 * {@code TagVocabularyTest} can check the two never drift apart.
 *
 * <p>The point of the annotations is that {@code @Smoek} does not compile. A
 * free-string tag makes every typo a test that silently belongs to no suite,
 * which is indistinguishable from a passing test in every report.
 *
 * <p>The vocabulary is deliberately small and grouped. Four layers, two depths,
 * four domains, two escape hatches. Adding a tag means adding a file here, which
 * is enough friction to make it a decision rather than a habit.
 */
public final class Tags {

    // Layer: which surface the test drives. One per module, so a layer tag also
    // selects a module - `-Ptags=api` legitimately runs nothing in ui-tests.
    public static final String UI = "ui";
    public static final String API = "api";
    public static final String DB = "db";
    public static final String CONTRACT = "contract";

    // Depth: how much of the suite a run is willing to pay for.
    public static final String SMOKE = "smoke";
    public static final String REGRESSION = "regression";

    // Domain: which part of the product. These are the ones that make a report
    // answer "what is broken" rather than "how many are red".
    public static final String STOREFRONT = "storefront";
    public static final String CHECKOUT = "checkout";
    public static final String ADMIN = "admin";
    public static final String AUTH = "auth";

    // Escape hatches. Both are admissions, and both are meant to be visible.
    public static final String SLOW = "slow";
    public static final String QUARANTINE = "quarantine";

    private Tags() {
    }
}
