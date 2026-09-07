package toolshop.automation.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Resolves {@link ToolshopConfig} from five layers and fails with every problem
 * it can find at once.
 *
 * <p>Precedence, highest first:
 *
 * <ol>
 *   <li>system properties, {@code -Dtoolshop.api.base-url=...}
 *   <li>environment variables, {@code TOOLSHOP_API_BASE_URL=...}
 *   <li>{@code .env.local}, gitignored, local development only
 *   <li>{@code application-<profile>.properties}
 *   <li>{@code application.properties}
 * </ol>
 *
 * <p>{@code .env.local} sits <em>below</em> real environment variables on purpose.
 * CI sets environment variables and has no such file, so the file cannot affect
 * CI, and it can never outrank a {@code -D}. It exists for one reason: it is
 * what makes an IDE gutter run work with no run configuration at all, because
 * nothing needs to be injected by the build.
 *
 * <p>All precedence lives here, in one class, and nowhere else. The build
 * forwards system properties that already exist on its own invocation and never
 * reads configuration or calls {@code System.setProperty}. See
 * {@code docs/adr/0002-gradle-never-owns-configuration.md}.
 */
final class ConfigLoader {

    static final String PROFILE_KEY = "toolshop.profile";
    static final String DEFAULT_PROFILE = "local";
    static final List<String> PROFILES = List.of("local", "ci", "hosted", "buggy");

    static final String DEFAULTS_FILE = "application.properties";
    static final String ENV_FILE = ".env.local";

    private static final String KEY_PREFIX = "toolshop.";
    private static final String ENV_PREFIX = "TOOLSHOP_";

    private ConfigLoader() {
    }

    static ToolshopConfig load() {
        return resolve(fromEnvironment());
    }

    // ---------------------------------------------------------------- sources

    /**
     * The raw material of one resolution.
     *
     * <p>Captured as a value so that precedence can be unit tested without
     * mutating the real environment - which is untestable in-process, and is
     * exactly the sort of global mutation this design is arguing against.
     *
     * @param systemProperties {@code toolshop.*} system properties, dotted keys
     * @param environment      {@code TOOLSHOP_*} variables, uppercase keys
     * @param envLocal         contents of {@code .env.local}, uppercase keys
     * @param envLocalPath     where the file was found, or null if there is none
     * @param propertyFiles    resource name to contents, every profile file that
     *                         exists and not only the active one, because rule 2
     *                         must hold for files this run does not read
     */
    record Sources(Map<String, String> systemProperties,
                   Map<String, String> environment,
                   Map<String, String> envLocal,
                   Path envLocalPath,
                   Map<String, Map<String, String>> propertyFiles) {
    }

    static Sources fromEnvironment() {
        Map<String, String> systemProperties = new TreeMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(KEY_PREFIX)) {
                systemProperties.put(name, System.getProperty(name));
            }
        }

        Map<String, String> environment = new TreeMap<>();
        System.getenv().forEach((name, value) -> {
            if (name.startsWith(ENV_PREFIX)) {
                environment.put(name, value);
            }
        });

        Path envFile = findEnvFile();
        Map<String, String> envLocal = envFile == null ? Map.of() : readEnvFile(envFile);

        return new Sources(systemProperties, environment, envLocal, envFile, propertyFilesFromClasspath());
    }

    /**
     * Searches upward for {@code .env.local} from the working directory.
     *
     * <p>Upward rather than at a fixed path because the working directory differs
     * between a Gradle test task, which uses the module directory, and an IDE
     * gutter run. Pinning the test task's working directory to the repository
     * root would fix one and break the other, and the gutter run is the case
     * this file exists to serve.
     */
    private static Path findEnvFile() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(ENV_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static Map<String, Map<String, String>> propertyFilesFromClasspath() {
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        putIfPresent(files, DEFAULTS_FILE);
        for (String profile : PROFILES) {
            putIfPresent(files, profileFile(profile));
        }
        return files;
    }

    static String profileFile(String profile) {
        return "application-" + profile + ".properties";
    }

    private static void putIfPresent(Map<String, Map<String, String>> files, String resource) {
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                files.put(resource, toMap(in));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource + " from the classpath", e);
        }
    }

    private static Map<String, String> readEnvFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return toMap(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /**
     * Both file formats are read by {@link Properties}, which is in the JDK and
     * already handles {@code key=value}, {@code #} comments and encoding. A
     * dotenv parser would be a dependency for syntax we already have.
     */
    private static Map<String, String> toMap(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(in);
        Map<String, String> values = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name));
        }
        return values;
    }

    // --------------------------------------------------------------- resolution

    static ToolshopConfig resolve(Sources sources) {
        List<String> problems = new ArrayList<>();

        checkKeyShape(sources, problems);
        checkNoSecretsInFiles(sources, problems);

        String profile = resolveProfile(sources, problems);
        if (!sources.propertyFiles().containsKey(profileFile(profile))) {
            problems.add("profile '" + profile + "' is active but " + profileFile(profile)
                    + " is not on the classpath.");
        }

        List<Tier> tiers = tiers(sources, profile);

        Map<ConfigKey, Resolved> values = new EnumMap<>(ConfigKey.class);
        for (ConfigKey key : ConfigKey.values()) {
            for (Tier tier : tiers) {
                Resolved found = tier.get(key);
                if (found != null) {
                    values.put(key, found);
                    break;
                }
            }
            if (!values.containsKey(key)) {
                problems.add(missing(key));
            }
        }

        URI uiBaseUrl = url(values, ConfigKey.UI_BASE_URL, problems);
        URI apiBaseUrl = url(values, ConfigKey.API_BASE_URL, problems);
        Duration uiTimeout = duration(values, ConfigKey.UI_TIMEOUT, problems);
        Duration apiTimeout = duration(values, ConfigKey.API_TIMEOUT, problems);
        Boolean headless = flag(values, ConfigKey.UI_HEADLESS, problems);
        String adminEmail = text(values, ConfigKey.ADMIN_EMAIL, problems);
        String adminPassword = text(values, ConfigKey.ADMIN_PASSWORD, problems);
        String customerEmail = text(values, ConfigKey.CUSTOMER_EMAIL, problems);
        String customerPassword = text(values, ConfigKey.CUSTOMER_PASSWORD, problems);

        if (!problems.isEmpty()) {
            throw new ConfigurationException(report(problems, profile, tiers));
        }

        return new ToolshopConfig(
                profile,
                uiBaseUrl,
                apiBaseUrl,
                uiTimeout,
                apiTimeout,
                headless,
                new ToolshopConfig.Credentials(adminEmail, adminPassword),
                new ToolshopConfig.Credentials(customerEmail, customerPassword));
    }

    /** A value and the tier it came from. The provenance is half the diagnostic. */
    private record Resolved(String value, String source) {
    }

    /**
     * One precedence layer. {@code environmentShaped} tiers are keyed by
     * {@code TOOLSHOP_API_BASE_URL}; the rest by {@code toolshop.api.base-url}.
     * Lookup goes key to variable name and never the reverse, which is why
     * {@link #checkKeyShape} can insist on lowercase keys.
     */
    private record Tier(String label, boolean environmentShaped, Map<String, String> values, String detail) {

        Resolved get(ConfigKey key) {
            return get(key.key());
        }

        Resolved get(String dottedKey) {
            String name = environmentShaped ? ConfigKey.envName(dottedKey) : dottedKey;
            String value = values.get(name);
            return value == null ? null : new Resolved(value, label);
        }
    }

    private static List<Tier> overrideTiers(Sources sources) {
        String envLocalDetail;
        if (sources.envLocalPath() != null) {
            envLocalDetail = count(sources.envLocal().size(), "key") + " from " + sources.envLocalPath();
        } else if (!sources.envLocal().isEmpty()) {
            envLocalDetail = count(sources.envLocal().size(), "key") + " supplied directly";
        } else {
            envLocalDetail = "not found, searched upward from " + Path.of("").toAbsolutePath();
        }

        return List.of(
                new Tier("system properties", false, sources.systemProperties(),
                        count(sources.systemProperties().size(), "toolshop.* key")),
                new Tier("environment variables", true, sources.environment(),
                        count(sources.environment().size(), "TOOLSHOP_* variable") + ", as seen by this JVM"),
                new Tier(ENV_FILE, true, sources.envLocal(), envLocalDetail));
    }

    private static List<Tier> tiers(Sources sources, String profile) {
        List<Tier> tiers = new ArrayList<>(overrideTiers(sources));
        for (String resource : List.of(profileFile(profile), DEFAULTS_FILE)) {
            Map<String, String> values = sources.propertyFiles().getOrDefault(resource, Map.of());
            String detail = sources.propertyFiles().containsKey(resource)
                    ? count(values.size(), "key")
                    : "not on the classpath";
            tiers.add(new Tier(resource, false, values, detail));
        }
        return List.copyOf(tiers);
    }

    private static String resolveProfile(Sources sources, List<String> problems) {
        for (Tier tier : overrideTiers(sources)) {
            Resolved found = tier.get(PROFILE_KEY);
            if (found == null) {
                continue;
            }
            if (PROFILES.contains(found.value())) {
                return found.value();
            }
            // Report it and carry on with the default, so the output is one clear
            // problem rather than that plus a missing value for every key the
            // absent profile file would have supplied.
            problems.add(PROFILE_KEY + " is '" + found.value() + "' (from " + found.source()
                    + "), which is not a known profile. Valid profiles: " + String.join(", ", PROFILES)
                    + ". The report below falls back to '" + DEFAULT_PROFILE + "'.");
            return DEFAULT_PROFILE;
        }
        return DEFAULT_PROFILE;
    }

    // --------------------------------------------------------------- validation

    /**
     * Rule: keys are lowercase, hyphen-separated, and recognised.
     *
     * <p>Environment variables are deliberately not checked for being recognised.
     * {@code TOOLSHOP_SUT_DIR} and {@code TOOLSHOP_READY_TIMEOUT} belong to
     * {@code ./run} and {@code scripts/wait-for-sut.sh}, not to this loader, and
     * rejecting them would couple the two. A mistyped {@code TOOLSHOP_*} variable
     * therefore goes unnoticed here - but the key it was meant to supply is then
     * missing, and that failure names the exact variable to set.
     */
    private static void checkKeyShape(Sources sources, List<String> problems) {
        sources.systemProperties().keySet().forEach(key -> {
            if (hasUppercase(key)) {
                problems.add(uppercaseProblem(key, "a system property"));
            } else if (!PROFILE_KEY.equals(key) && ConfigKey.byKey(key).isEmpty()) {
                problems.add("-D" + key + " is not a recognised configuration key, so it overrides nothing."
                        + " Recognised keys are listed in docs/CONFIGURATION.md.");
            }
        });

        sources.propertyFiles().forEach((resource, values) -> values.keySet().forEach(key -> {
            if (hasUppercase(key)) {
                problems.add(uppercaseProblem(key, resource));
            } else if (PROFILE_KEY.equals(key)) {
                problems.add(resource + " sets " + PROFILE_KEY + ". The active profile selects which file"
                        + " is read, so it cannot be set from inside one. Use -D" + PROFILE_KEY
                        + "=<profile> or " + ConfigKey.envName(PROFILE_KEY) + "=<profile>.");
            } else if (ConfigKey.byKey(key).isEmpty()) {
                problems.add(resource + " sets '" + key + "', which is not a recognised configuration key.");
            }
        }));
    }

    /**
     * Rule: a secret key appearing in any committed properties file is an error,
     * whether or not this run reads that file.
     *
     * <p>This is the structural answer to a placeholder credential. A suite that
     * ships {@code password=dummypassword} as a default resolves it happily,
     * authenticates as nobody, and fails somewhere downstream looking like a
     * product bug. There is no value a secret key can carry in a committed file
     * that is better than the absence of one.
     */
    private static void checkNoSecretsInFiles(Sources sources, List<String> problems) {
        sources.propertyFiles().forEach((resource, values) -> {
            for (ConfigKey key : ConfigKey.values()) {
                if (key.secret() && values.containsKey(key.key())) {
                    problems.add(resource + " sets " + key.key() + ", which is a secret and must never appear"
                            + " in a committed file. Supply it as " + key.environmentVariable()
                            + " in the environment, or in " + ENV_FILE + " for local development.");
                }
            }
        });
    }

    private static boolean hasUppercase(String key) {
        return key.chars().anyMatch(Character::isUpperCase);
    }

    private static String uppercaseProblem(String key, String where) {
        return "'" + key + "' (" + where + ") contains an uppercase character. Keys must be lowercase and"
                + " hyphen-separated, because '" + key + "' maps to " + ConfigKey.envName(key)
                + " and that cannot be mapped back to a single key.";
    }

    private static String missing(ConfigKey key) {
        if (!key.secret()) {
            return key.key() + " has no value in any source. A non-secret key is expected to have a default"
                    + " in " + DEFAULTS_FILE + ", or a target-specific value in application-<profile>.properties.";
        }
        return key.key() + " is required and has no default anywhere. Supply it as -D" + key.key()
                + "=<value>, or as " + key.environmentVariable() + "=<value> in the environment, which is how"
                + " CI supplies it, or as the same variable in " + ENV_FILE + " at the repository root, which"
                + " is for local development only and can never outrank a -D.";
    }

    // ------------------------------------------------------------- conversion

    private static String text(Map<ConfigKey, Resolved> values, ConfigKey key, List<String> problems) {
        Resolved resolved = values.get(key);
        if (resolved == null) {
            return null;
        }
        if (resolved.value().isBlank()) {
            problems.add(key.key() + " is set but empty (from " + resolved.source() + ").");
            return null;
        }
        return resolved.value();
    }

    private static URI url(Map<ConfigKey, Resolved> values, ConfigKey key, List<String> problems) {
        String value = text(values, key, problems);
        if (value == null) {
            return null;
        }
        // A trailing slash is stripped so that callers can append an absolute
        // path without producing a double slash, and so that the same target
        // written either way resolves to one value.
        String trimmed = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            problems.add(shapeProblem(key, values, "an absolute http or https URL", value) + " " + e.getReason());
            return null;
        }
        if (!uri.isAbsolute() || uri.getHost() == null
                || !List.of("http", "https").contains(String.valueOf(uri.getScheme()))) {
            problems.add(shapeProblem(key, values, "an absolute http or https URL with a host", value));
            return null;
        }
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
            problems.add(shapeProblem(key, values, "a port between 1 and 65535", value));
            return null;
        }
        return uri;
    }

    private static Duration duration(Map<ConfigKey, Resolved> values, ConfigKey key, List<String> problems) {
        String value = text(values, key, problems);
        if (value == null) {
            return null;
        }
        try {
            Duration duration = Duration.parse(value);
            if (duration.isNegative() || duration.isZero()) {
                problems.add(shapeProblem(key, values, "a positive duration", value));
                return null;
            }
            return duration;
        } catch (DateTimeParseException e) {
            problems.add(shapeProblem(key, values, "an ISO-8601 duration such as PT30S or PT2M", value));
            return null;
        }
    }

    /**
     * Accepts exactly {@code true} or {@code false}. Not
     * {@code Boolean.parseBoolean}, which maps every typo to {@code false} - so
     * {@code -Dtoolshop.ui.headless=fasle} would silently open a browser window
     * on a CI runner and time out with no indication why.
     */
    private static Boolean flag(Map<ConfigKey, Resolved> values, ConfigKey key, List<String> problems) {
        String value = text(values, key, problems);
        if (value == null) {
            return null;
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.valueOf(value);
        }
        problems.add(shapeProblem(key, values, "exactly 'true' or 'false'", value));
        return null;
    }

    private static String shapeProblem(ConfigKey key, Map<ConfigKey, Resolved> values, String expected, String value) {
        return key.key() + " must be " + expected + ", but is '" + value + "' (from "
                + values.get(key).source() + ").";
    }

    // ----------------------------------------------------------------- report

    /**
     * One message carrying every problem, plus what was searched and in what
     * order.
     *
     * <p>The sources block is the part that earns its keep. A wrong profile, a
     * {@code .env.local} that is not where it was assumed to be, and a Gradle
     * daemon holding a stale environment all look identical from a missing value
     * alone, and all three are readable straight off the key counts.
     */
    private static String report(List<String> problems, String profile, List<Tier> tiers) {
        StringBuilder out = new StringBuilder(512);
        out.append("Toolshop configuration is invalid. ")
                .append(count(problems.size(), "problem")).append(":\n\n");

        for (int i = 0; i < problems.size(); i++) {
            out.append(String.format("  %d. %s%n", i + 1, wrap(problems.get(i), 5)));
        }

        out.append("\nSources searched, highest precedence first:\n");
        for (int i = 0; i < tiers.size(); i++) {
            out.append(String.format("  %d  %-32s %s%n", i + 1, tiers.get(i).label(), tiers.get(i).detail()));
        }

        out.append("\nActive profile: ").append(profile).append("   (-D").append(PROFILE_KEY)
                .append("=<profile>, default '").append(DEFAULT_PROFILE).append("', one of ")
                .append(String.join(", ", PROFILES)).append(")\n");

        out.append("\nNo test ran. This check happens before test discovery, so none of the above\n")
                .append("is a product failure - it is this run's configuration.\n");

        return out.toString();
    }

    /** Wraps a problem to a readable width, indenting continuations under the number. */
    private static String wrap(String problem, int indent) {
        String pad = " ".repeat(indent);
        StringBuilder out = new StringBuilder(problem.length() + 16);
        int lineLength = indent;
        for (String word : problem.split(" ")) {
            if (lineLength + word.length() + 1 > 88) {
                out.append('\n').append(pad);
                lineLength = indent;
            } else if (lineLength > indent) {
                out.append(' ');
                lineLength++;
            }
            out.append(word);
            lineLength += word.length();
        }
        return out.toString();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
