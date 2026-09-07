package toolshop.automation.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The precedence merge and the validation rules, exercised without touching the
 * real environment.
 *
 * <p>Worth this much attention for one reason: a wrong precedence order is the
 * exact defect the whole configuration design exists to prevent, and it is
 * invisible. An override that silently does not apply produces a run that passes
 * or fails for reasons unrelated to what was typed on the command line.
 */
class ConfigLoaderTest {

    // -------------------------------------------------------------- precedence

    @Nested
    @DisplayName("precedence, highest wins")
    class Precedence {

        @Test
        void systemPropertyBeatsEveryOtherSource() {
            // Given the same key set in all five layers
            ConfigLoader.Sources sources = sources()
                    .systemProperty("toolshop.api.base-url", "http://from-system-property:1")
                    .environment("TOOLSHOP_API_BASE_URL", "http://from-environment:2")
                    .envLocal("TOOLSHOP_API_BASE_URL", "http://from-env-local:3")
                    .profileFileEntry("toolshop.api.base-url", "http://from-profile-file:4")
                    .defaultsEntry("toolshop.api.base-url", "http://from-defaults:5")
                    .build();

            // When resolved
            ToolshopConfig config = ConfigLoader.resolve(sources);

            // Then the system property wins
            assertEquals("http://from-system-property:1", config.apiBaseUrl().toString());
        }

        @Test
        void environmentBeatsEnvLocalAndFiles() {
            ToolshopConfig config = ConfigLoader.resolve(sources()
                    .environment("TOOLSHOP_API_BASE_URL", "http://from-environment:2")
                    .envLocal("TOOLSHOP_API_BASE_URL", "http://from-env-local:3")
                    .profileFileEntry("toolshop.api.base-url", "http://from-profile-file:4")
                    .build());

            assertEquals("http://from-environment:2", config.apiBaseUrl().toString());
        }

        /**
         * The layer that most needs pinning down. A local file that outranked a
         * real environment variable would make CI unreproducible, and one that
         * outranked a -D would reproduce the defect this design was written
         * against.
         */
        @Test
        void envLocalBeatsFilesButNothingAboveIt() {
            ToolshopConfig config = ConfigLoader.resolve(sources()
                    .envLocal("TOOLSHOP_API_BASE_URL", "http://from-env-local:3")
                    .profileFileEntry("toolshop.api.base-url", "http://from-profile-file:4")
                    .defaultsEntry("toolshop.api.base-url", "http://from-defaults:5")
                    .build());

            assertEquals("http://from-env-local:3", config.apiBaseUrl().toString());
        }

        @Test
        void profileFileBeatsDefaults() {
            ToolshopConfig config = ConfigLoader.resolve(sources()
                    .profileFileEntry("toolshop.ui.timeout", "PT1M")
                    .defaultsEntry("toolshop.ui.timeout", "PT5S")
                    .build());

            assertEquals(Duration.ofMinutes(1), config.uiTimeout());
        }

        @Test
        void defaultsApplyWhenNothingOverridesThem() {
            ToolshopConfig config = ConfigLoader.resolve(sources().build());

            assertEquals(Duration.ofSeconds(30), config.uiTimeout());
        }
    }

    // ----------------------------------------------------------------- profile

    @Nested
    @DisplayName("profile selection")
    class Profiles {

        @Test
        void defaultsToLocalWhenNothingSelectsOne() {
            assertEquals("local", ConfigLoader.resolve(sources().build()).profile());
        }

        @Test
        void selectsTheMatchingProfileFile() {
            ConfigLoader.Sources sources = sources()
                    .systemProperty(ConfigLoader.PROFILE_KEY, "hosted")
                    .file("application-hosted.properties",
                            Map.of("toolshop.ui.base-url", "https://hosted.example",
                                    "toolshop.api.base-url", "https://api.hosted.example"))
                    .build();

            ToolshopConfig config = ConfigLoader.resolve(sources);

            assertEquals("hosted", config.profile());
            assertEquals("https://api.hosted.example", config.apiBaseUrl().toString());
        }

        @Test
        void anUnknownProfileNamesTheValidOnes() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .systemProperty(ConfigLoader.PROFILE_KEY, "staging")
                            .build()));

            assertProblem(failure, "is not a known profile");
            assertProblem(failure, "local, ci, hosted, buggy");
        }

        @Test
        void aProfileFileCannotSetTheProfile() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .defaultsEntry(ConfigLoader.PROFILE_KEY, "hosted")
                            .build()));

            assertProblem(failure, "cannot be set from inside one");
        }

        @Test
        void aMissingProfileFileIsReportedAsSuch() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .environment(ConfigKey.envName(ConfigLoader.PROFILE_KEY), "buggy")
                            .build()));

            assertProblem(failure, "application-buggy.properties is not on the classpath");
        }
    }

    // --------------------------------------------------------------- key shape

    @Nested
    @DisplayName("key shape")
    class KeyShape {

        @Test
        void aCamelCaseKeyInAFileIsRejected() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .defaultsEntry("toolshop.api.baseUrl", "http://localhost:8091")
                            .build()));

            assertProblem(failure, "contains an uppercase character");
            assertProblem(failure, "TOOLSHOP_API_BASEURL");
        }

        @Test
        void aCamelCaseSystemPropertyIsRejected() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .systemProperty("toolshop.ui.headLess", "true")
                            .build()));

            assertProblem(failure, "contains an uppercase character");
        }

        /** A -D that overrides nothing is as damaging as one that is missing. */
        @Test
        void anUnrecognisedSystemPropertyIsRejected() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .systemProperty("toolshop.api.timeuot", "PT5S")
                            .build()));

            assertProblem(failure, "-Dtoolshop.api.timeuot is not a recognised configuration key");
        }

        @Test
        void anUnrecognisedKeyInAFileIsRejected() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .defaultsEntry("toolshop.retries", "3")
                            .build()));

            assertProblem(failure, "'toolshop.retries', which is not a recognised configuration key");
        }

        /**
         * Not rejected on purpose: TOOLSHOP_SUT_DIR and TOOLSHOP_READY_TIMEOUT
         * belong to ./run and the readiness script, and coupling the loader to
         * those would mean the loader had to be edited to add a shell variable.
         */
        @Test
        void anUnrecognisedEnvironmentVariableIsIgnored() {
            ToolshopConfig config = ConfigLoader.resolve(sources()
                    .environment("TOOLSHOP_SUT_DIR", "../practice-software-testing")
                    .build());

            assertEquals("local", config.profile());
        }
    }

    // ----------------------------------------------------------------- secrets

    @Nested
    @DisplayName("secrets")
    class Secrets {

        @Test
        void aMissingSecretNamesTheKeyAndAllThreeWaysToSupplyIt() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources().withoutSecrets().build()));

            assertProblem(failure, "toolshop.admin.password is required and has no default");
            assertProblem(failure, "-Dtoolshop.admin.password=<value>");
            assertProblem(failure, "TOOLSHOP_ADMIN_PASSWORD=<value>");
            assertProblem(failure, ".env.local");
        }

        @Test
        void aSecretInACommittedFileIsAnError() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .defaultsEntry("toolshop.admin.password", "dummypassword")
                            .build()));

            assertProblem(failure, "which is a secret and must never appear in a committed file");
        }

        /**
         * The rule holds for files this run does not read. A placeholder parked
         * in a profile nobody currently uses is the same defect on a delay.
         */
        @Test
        void aSecretInAnInactiveProfileFileIsAlsoAnError() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .file("application-hosted.properties",
                                    Map.of("toolshop.customer.password", "a-committed-placeholder"))
                            .build()));

            assertProblem(failure, "application-hosted.properties sets toolshop.customer.password");
        }

        @Test
        void anEmptySecretIsNotASuppliedSecret() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .environment("TOOLSHOP_ADMIN_PASSWORD", "")
                            .build()));

            assertProblem(failure, "toolshop.admin.password is set but empty");
        }

        @Test
        void aPasswordIsNotPrintedByToString() {
            ToolshopConfig config = ConfigLoader.resolve(sources().build());

            assertTrue(config.toString().contains("******"),
                    () -> "expected a redacted password, got: " + config);
            assertTrue(!config.toString().contains("a-secret"),
                    () -> "the password reached toString: " + config);
        }
    }

    // ------------------------------------------------------------------- shape

    @Nested
    @DisplayName("value shape")
    class Shapes {

        @Test
        void aRelativeBaseUrlIsRejected() {
            assertProblem(assertThrows(ConfigurationException.class,
                            () -> ConfigLoader.resolve(sources()
                                    .profileFileEntry("toolshop.api.base-url", "localhost:8091")
                                    .build())),
                    "must be an absolute http or https URL");
        }

        @Test
        void aNonHttpSchemeIsRejected() {
            assertProblem(assertThrows(ConfigurationException.class,
                            () -> ConfigLoader.resolve(sources()
                                    .profileFileEntry("toolshop.ui.base-url", "ftp://localhost:4200")
                                    .build())),
                    "must be an absolute http or https URL");
        }

        @Test
        void aPortOutOfRangeIsRejected() {
            assertProblem(assertThrows(ConfigurationException.class,
                            () -> ConfigLoader.resolve(sources()
                                    .profileFileEntry("toolshop.api.base-url", "http://localhost:99999")
                                    .build())),
                    "must be a port between 1 and 65535");
        }

        @Test
        void aTrailingSlashIsStrippedSoTargetsWrittenEitherWayMatch() {
            ToolshopConfig config = ConfigLoader.resolve(sources()
                    .profileFileEntry("toolshop.api.base-url", "http://localhost:8091/")
                    .build());

            assertEquals("http://localhost:8091", config.apiBaseUrl().toString());
        }

        @Test
        void anUnparseableDurationIsRejected() {
            assertProblem(assertThrows(ConfigurationException.class,
                            () -> ConfigLoader.resolve(sources()
                                    .defaultsEntry("toolshop.api.timeout", "10s")
                                    .build())),
                    "must be an ISO-8601 duration such as PT30S or PT2M");
        }

        @Test
        void aZeroDurationIsRejected() {
            assertProblem(assertThrows(ConfigurationException.class,
                            () -> ConfigLoader.resolve(sources()
                                    .defaultsEntry("toolshop.api.timeout", "PT0S")
                                    .build())),
                    "must be a positive duration");
        }

        /**
         * Boolean.parseBoolean maps every typo to false, so a mistyped headless
         * flag would open a browser on a CI runner and time out with no clue as
         * to why. This is the whole reason the loader parses booleans itself.
         */
        @Test
        void aMistypedBooleanIsRejectedRatherThanTreatedAsFalse() {
            assertProblem(assertThrows(ConfigurationException.class,
                            () -> ConfigLoader.resolve(sources()
                                    .systemProperty("toolshop.ui.headless", "fasle")
                                    .build())),
                    "must be exactly 'true' or 'false'");
        }
    }

    // -------------------------------------------------------- optional keys

    @Nested
    @DisplayName("optional keys")
    class OptionalKeys {

        /**
         * The database and mail catcher exist for a local or CI target and not
         * for the hosted one, so their absence is a configuration that resolves
         * rather than a configuration that fails.
         */
        @Test
        void aTargetWithNoDatabaseOrMailCatcherStillResolves() {
            ToolshopConfig config = ConfigLoader.resolve(sources().build());

            assertTrue(config.database().isEmpty(), "no database keys were supplied");
            assertTrue(config.mailBaseUrl().isEmpty(), "no mail keys were supplied");
        }

        @Test
        void aFullySuppliedDatabaseGroupResolves() {
            ToolshopConfig config = ConfigLoader.resolve(sources()
                    .profileFileEntry("toolshop.db.url", "jdbc:mariadb://localhost:3306/toolshop")
                    .profileFileEntry("toolshop.db.username", "user")
                    .environment("TOOLSHOP_DB_PASSWORD", "a-secret")
                    .build());

            assertTrue(config.database().isPresent(), "the database should have resolved");
            assertEquals("jdbc:mariadb://localhost:3306/toolshop", config.database().orElseThrow().jdbcUrl());
        }

        /**
         * Half a database is worse than none: it fails at the first query as a
         * connection error rather than here as a configuration error.
         */
        @Test
        void aUrlWithNoCredentialsIsAnError() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .profileFileEntry("toolshop.db.url", "jdbc:mariadb://localhost:3306/toolshop")
                            .build()));

            assertProblem(failure, "toolshop.db.url is set, so the credentials are required too");
            assertProblem(failure, "Missing: toolshop.db.username, toolshop.db.password");
        }

        /**
         * The case that made the rule anchor on the URL. A developer's
         * .env.local keeps TOOLSHOP_DB_PASSWORD set permanently; switching to a
         * profile with no database must not fail over it.
         */
        @Test
        void aLeftoverPasswordWithNoUrlIsIgnored() {
            ToolshopConfig config = ConfigLoader.resolve(sources()
                    .environment("TOOLSHOP_DB_PASSWORD", "still-in-my-env-local")
                    .build());

            assertTrue(config.database().isEmpty(),
                    "a password with no URL does not describe a database");
        }

        @Test
        void aDatabaseUrlThatIsNotAJdbcUrlIsRejected() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .profileFileEntry("toolshop.db.url", "mariadb://localhost:3306/toolshop")
                            .profileFileEntry("toolshop.db.username", "user")
                            .environment("TOOLSHOP_DB_PASSWORD", "a-secret")
                            .build()));

            assertProblem(failure, "must be a JDBC URL beginning 'jdbc:'");
        }

        /** Optional does not exempt a secret from the committed-file rule. */
        @Test
        void anOptionalSecretStillCannotAppearInACommittedFile() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .defaultsEntry("toolshop.db.password", "root")
                            .build()));

            assertProblem(failure, "toolshop.db.password, which is a secret");
        }

        @Test
        void aMailBaseUrlIsShapeCheckedLikeAnyOtherUrl() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .profileFileEntry("toolshop.mail.base-url", "localhost:1080")
                            .build()));

            assertProblem(failure, "must be an absolute http or https URL");
        }
    }

    // ------------------------------------------------------------------ report

    @Nested
    @DisplayName("the failure report")
    class Report {

        @Test
        void collectsEveryProblemRatherThanOnlyTheFirst() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .withoutSecrets()
                            .profileFileEntry("toolshop.api.base-url", "not-a-url")
                            .defaultsEntry("toolshop.api.timeout", "10s")
                            .build()));

            assertProblem(failure, "4 problems");
            assertProblem(failure, "toolshop.admin.password");
            assertProblem(failure, "toolshop.customer.password");
            assertProblem(failure, "toolshop.api.base-url");
            assertProblem(failure, "toolshop.api.timeout");
        }

        /**
         * The block that makes a wrong profile, a .env.local that is not where it
         * was assumed to be, and a Gradle daemon holding a stale environment
         * distinguishable from each other. From a missing value alone they are
         * identical.
         */
        @Test
        void namesEverySourceSearchedInPrecedenceOrder() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources().withoutSecrets().build()));

            String message = failure.getMessage();
            assertProblem(failure, "Sources searched, highest precedence first");
            assertTrue(indexOfAll(message,
                            "system properties",
                            "environment variables",
                            ".env.local",
                            "application-local.properties",
                            "application.properties"),
                    () -> "sources are not listed in precedence order:\n" + message);
            assertProblem(failure, "Active profile: local");
            assertProblem(failure, "No test ran.");
        }

        @Test
        void reportsPerTierKeyCounts() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .withoutSecrets()
                            .systemProperty("toolshop.ui.headless", "false")
                            .build()));

            assertProblem(failure, "1 toolshop.* key");
            assertProblem(failure, "0 TOOLSHOP_* variables");
        }

        @Test
        void saysWhereEnvLocalWasLookedFor() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources().withoutSecrets().build()));

            assertProblem(failure, "not found, searched upward from");
        }

        @Test
        void namesTheSourceOfAWrongValue() {
            ConfigurationException failure = assertThrows(ConfigurationException.class,
                    () -> ConfigLoader.resolve(sources()
                            .systemProperty("toolshop.api.timeout", "10s")
                            .build()));

            assertProblem(failure, "(from system properties)");
        }
    }

    // ----------------------------------------------------- the committed files

    /**
     * The layers above are synthetic. This one resolves the real
     * {@code application*.properties} from the classpath, so a typo in a
     * committed profile file fails here rather than in the first test that uses
     * the value.
     */
    @Nested
    @DisplayName("the committed profile files")
    class CommittedFiles {

        /**
         * Every secret the committed files expect, and nothing else. The point
         * of these tests is that a profile resolves given only what the
         * environment is supposed to supply.
         */
        private final Map<String, String> SECRETS_FROM_THE_ENVIRONMENT = Map.of(
                "TOOLSHOP_ADMIN_PASSWORD", "a-secret",
                "TOOLSHOP_CUSTOMER_PASSWORD", "a-secret",
                "TOOLSHOP_DB_PASSWORD", "a-secret");

        @Test
        void everyProfileResolvesWithOnlySecretsSupplied() {
            for (String profile : ConfigLoader.PROFILES) {
                ConfigLoader.Sources sources = new ConfigLoader.Sources(
                        Map.of(ConfigLoader.PROFILE_KEY, profile),
                        SECRETS_FROM_THE_ENVIRONMENT,
                        Map.of(),
                        null,
                        ConfigLoader.propertyFilesFromClasspath());

                ToolshopConfig config = ConfigLoader.resolve(sources);

                assertEquals(profile, config.profile());
                assertTrue(config.apiBaseUrl().isAbsolute(),
                        () -> profile + " resolved a non-absolute api base url: " + config.apiBaseUrl());
                assertTrue(config.uiBaseUrl().isAbsolute(),
                        () -> profile + " resolved a non-absolute ui base url: " + config.uiBaseUrl());
            }
        }

        @Test
        void theLocalProfilePointsAtTheContainers() {
            ConfigLoader.Sources sources = new ConfigLoader.Sources(
                    Map.of(),
                    SECRETS_FROM_THE_ENVIRONMENT,
                    Map.of(),
                    null,
                    ConfigLoader.propertyFilesFromClasspath());

            ToolshopConfig config = ConfigLoader.resolve(sources);

            assertEquals("http://localhost:4200", config.uiBaseUrl().toString());
            assertEquals("http://localhost:8091", config.apiBaseUrl().toString());
            assertTrue(config.headless(), "headless is the default everywhere, including locally");
        }
    }

    // ------------------------------------------------------------------ support

    private static void assertProblem(ConfigurationException failure, String expected) {
        // The message is wrapped for readability, so a fragment can straddle a
        // line break. Compare on a single-spaced form.
        String flattened = failure.getMessage().replaceAll("\\s+", " ");
        assertTrue(flattened.contains(expected),
                () -> "expected the report to mention \"" + expected + "\", but it said:\n\n"
                        + failure.getMessage());
    }

    private static boolean indexOfAll(String message, String... inOrder) {
        int from = 0;
        for (String fragment : inOrder) {
            int at = message.indexOf(fragment, from);
            if (at < 0) {
                return false;
            }
            from = at + fragment.length();
        }
        return true;
    }

    private static SourcesBuilder sources() {
        return new SourcesBuilder();
    }

    /**
     * Builds {@link ConfigLoader.Sources} that resolve cleanly, so each test
     * changes one thing and the failure names that thing.
     */
    private static final class SourcesBuilder {

        private final Map<String, String> systemProperties = new LinkedHashMap<>();
        private final Map<String, String> environment = new LinkedHashMap<>();
        private final Map<String, String> envLocal = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> files = new LinkedHashMap<>();
        private final Map<String, String> defaults = new LinkedHashMap<>();
        private final Map<String, String> profileFile = new LinkedHashMap<>();
        private Path envLocalPath;

        private SourcesBuilder() {
            defaults.put("toolshop.ui.timeout", "PT30S");
            defaults.put("toolshop.api.timeout", "PT10S");
            defaults.put("toolshop.ui.headless", "true");
            defaults.put("toolshop.admin.email", "admin@example.test");
            defaults.put("toolshop.customer.email", "customer@example.test");
            profileFile.put("toolshop.ui.base-url", "http://localhost:4200");
            profileFile.put("toolshop.api.base-url", "http://localhost:8091");
            environment.put("TOOLSHOP_ADMIN_PASSWORD", "a-secret");
            environment.put("TOOLSHOP_CUSTOMER_PASSWORD", "a-secret");
        }

        SourcesBuilder systemProperty(String key, String value) {
            systemProperties.put(key, value);
            return this;
        }

        SourcesBuilder environment(String name, String value) {
            environment.put(name, value);
            return this;
        }

        SourcesBuilder envLocal(String name, String value) {
            envLocal.put(name, value);
            envLocalPath = Path.of("/repo/.env.local");
            return this;
        }

        SourcesBuilder defaultsEntry(String key, String value) {
            defaults.put(key, value);
            return this;
        }

        SourcesBuilder profileFileEntry(String key, String value) {
            profileFile.put(key, value);
            return this;
        }

        SourcesBuilder file(String resource, Map<String, String> values) {
            files.put(resource, values);
            return this;
        }

        SourcesBuilder withoutSecrets() {
            environment.remove("TOOLSHOP_ADMIN_PASSWORD");
            environment.remove("TOOLSHOP_CUSTOMER_PASSWORD");
            return this;
        }

        ConfigLoader.Sources build() {
            Map<String, Map<String, String>> allFiles = new LinkedHashMap<>();
            allFiles.put(ConfigLoader.DEFAULTS_FILE, defaults);
            allFiles.put(ConfigLoader.profileFile("local"), profileFile);
            allFiles.putAll(files);
            return new ConfigLoader.Sources(systemProperties, environment, envLocal, envLocalPath, allFiles);
        }
    }
}
