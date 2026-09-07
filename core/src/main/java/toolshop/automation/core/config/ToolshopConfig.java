package toolshop.automation.core.config;

import java.net.URI;
import java.time.Duration;

/**
 * The resolved configuration for one run: immutable, and resolved exactly once
 * per test JVM.
 *
 * <p>This is the only thing that differs between the three supported targets.
 * The same bytecode runs against local Docker, the hosted instance and the
 * deliberately buggy build; a target is a profile, not a branch. If test or page
 * code ever needs to ask which environment it is in, the abstraction is wrong.
 *
 * @param profile     the active profile, one of {@code local}, {@code ci}, {@code hosted}, {@code buggy}
 * @param uiBaseUrl   Angular storefront, no trailing slash
 * @param apiBaseUrl  REST API, no trailing slash. Note the API has no route at {@code /}
 * @param uiTimeout   budget for a UI expectation
 * @param apiTimeout  budget for an API call
 * @param headless    whether browsers launch headless
 * @param admin       a seeded account with administrative rights
 * @param customer    a seeded account with customer rights
 */
public record ToolshopConfig(
        String profile,
        URI uiBaseUrl,
        URI apiBaseUrl,
        Duration uiTimeout,
        Duration apiTimeout,
        boolean headless,
        Credentials admin,
        Credentials customer) {

    private static ToolshopConfig instance;

    /**
     * The configuration for this JVM, resolving and validating it on first call.
     *
     * <p>Synchronised rather than lazily initialised through a holder class: a
     * holder would wrap the failure in {@code ExceptionInInitializerError}, whose
     * own message is null, and the message is the entire point of this class
     * failing. The lock costs nothing at the frequency configuration is read.
     *
     * <p>{@link ConfigValidationListener} calls this before test discovery, so by
     * the time any test runs it has already succeeded. Tests will receive this
     * by constructor injection once the JUnit extensions land; a static reader is
     * how a run gets validated before there is anything to inject into.
     *
     * @throws ConfigurationException listing every problem found
     */
    public static synchronized ToolshopConfig get() {
        if (instance == null) {
            instance = ConfigLoader.load();
        }
        return instance;
    }

    /**
     * A seeded account.
     *
     * <p>{@code toString} redacts the password, so a record dropped into a log
     * line or an Allure attachment cannot leak it. The default record
     * {@code toString} would print it.
     */
    public record Credentials(String email, String password) {

        @Override
        public String toString() {
            return email + " / ******";
        }
    }
}
