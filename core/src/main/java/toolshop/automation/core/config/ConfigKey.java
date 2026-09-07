package toolshop.automation.core.config;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
    ADMIN_PASSWORD("toolshop.admin.password", Flag.SECRET),
    CUSTOMER_PASSWORD("toolshop.customer.password", Flag.SECRET),

    // Optional, because they describe things only some targets have. The
    // database and the mail catcher are containers on the same machine as a
    // local or CI run; against the hosted instance there is no database to
    // connect to and no mail server to read.
    //
    // Optional does NOT mean a test may quietly skip. A test that needs one of
    // these and cannot have it fails, naming the keys and the tag expression
    // that excludes it - see ToolshopDatabase and MailCatcher in the data
    // module. A silently skipped test is the same green-with-no-signal outcome
    // the tag guard exists to prevent.
    DB_URL("toolshop.db.url", Flag.OPTIONAL),
    DB_USERNAME("toolshop.db.username", Flag.OPTIONAL),
    DB_PASSWORD("toolshop.db.password", Flag.SECRET, Flag.OPTIONAL),
    MAIL_BASE_URL("toolshop.mail.base-url", Flag.OPTIONAL);

    /** Nested so that a call site above reads as what it means. */
    enum Flag {
        /** No default anywhere, and never in a committed file. */
        SECRET,
        /** May be absent, because not every target has the thing it describes. */
        OPTIONAL
    }

    private final String key;
    private final Set<Flag> flags;

    ConfigKey(String key, Flag... flags) {
        this.key = key;
        this.flags = flags.length == 0 ? Set.of() : Set.of(flags);
    }

    /** The dotted key, which is also the {@code -D} name. */
    public String key() {
        return key;
    }

    public boolean secret() {
        return flags.contains(Flag.SECRET);
    }

    public boolean optional() {
        return flags.contains(Flag.OPTIONAL);
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
