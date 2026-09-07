package toolshop.automation.core.config;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Every configuration key the framework recognises.
 *
 * <p>Keys are lowercase and hyphen-separated so that the dotted key <em>is</em> its
 * own {@code -D} name, and its environment-variable name is derivable:
 * {@code toolshop.api.base-url} maps to {@code TOOLSHOP_API_BASE_URL}. That
 * mapping only works in one direction. A camelCase key such as
 * {@code toolshop.api.baseUrl} would map to {@code TOOLSHOP_API_BASEURL}, which
 * cannot be mapped back unambiguously, so {@link ConfigLoader} rejects any key
 * containing an uppercase character instead of guessing.
 *
 * <p>Enumerating the keys is also what makes an unrecognised {@code toolshop.*}
 * system property an error rather than a silently ignored override. A typo in a
 * {@code -D} flag has the same effect as omitting it, so it has to be as loud.
 */
public enum ConfigKey {

    UI_BASE_URL("toolshop.ui.base-url"),
    API_BASE_URL("toolshop.api.base-url"),
    UI_TIMEOUT("toolshop.ui.timeout"),
    API_TIMEOUT("toolshop.api.timeout"),
    UI_HEADLESS("toolshop.ui.headless"),
    ADMIN_EMAIL("toolshop.admin.email"),
    CUSTOMER_EMAIL("toolshop.customer.email"),

    // Secrets. A secret has no default anywhere, and a secret key appearing in
    // any committed properties file is itself an error - ConfigLoader enforces
    // both. The seeded Toolshop passwords are published in the application's own
    // README, so nothing is protected by treating them this way. They are
    // treated this way regardless, because a rule that is relaxed for the values
    // that do not matter is not in place for the ones that do.
    ADMIN_PASSWORD("toolshop.admin.password", true),
    CUSTOMER_PASSWORD("toolshop.customer.password", true);

    private final String key;
    private final boolean secret;

    ConfigKey(String key) {
        this(key, false);
    }

    ConfigKey(String key, boolean secret) {
        this.key = key;
        this.secret = secret;
    }

    /** The dotted key, which is also the {@code -D} name. */
    public String key() {
        return key;
    }

    public boolean secret() {
        return secret;
    }

    /** {@code toolshop.api.base-url} to {@code TOOLSHOP_API_BASE_URL}. */
    public String environmentVariable() {
        return envName(key);
    }

    static String envName(String dottedKey) {
        return dottedKey.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    static Optional<ConfigKey> byKey(String dottedKey) {
        return Stream.of(values()).filter(candidate -> candidate.key.equals(dottedKey)).findFirst();
    }
}
