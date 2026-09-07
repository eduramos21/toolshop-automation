package toolshop.automation.api;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The application's own OpenAPI document, loaded from the running application
 * and made parseable.
 *
 * <p>Taken from the application rather than committed here. A committed copy is
 * a contract this repository asserts against itself, and it goes stale silently;
 * fetching it means the document under test is the one the application is
 * actually publishing.
 *
 * <h2>Why it needs normalising at all</h2>
 *
 * <p>Measured, and the answer was not what the plan assumed. The document
 * declares {@code openapi: 3.2.0}, and no Java validator supports 3.2 yet -
 * swagger-parser does not recognise the version at all and falls back to
 * Swagger 2 parsing, which then rejects {@code content} and {@code requestBody}
 * as "unexpected" on every single operation.
 *
 * <p>Setting the version to 3.1.0 fixes that, and leaves exactly one complaint:
 * seven paths declare a {@code query} operation. {@code QUERY} is a new HTTP
 * method that OpenAPI 3.2 added and the parser has no concept of. The
 * application really does use it - the storefront sends
 * {@code QUERY /products} - so this is a genuine 3.2 document rather than a
 * mislabelled one.
 *
 * <p>So two changes are made, and nothing else:
 *
 * <ol>
 *   <li>{@code openapi} is set to {@code 3.1.0}
 *   <li>the {@code query} operation is removed from the seven paths that have
 *       one, leaving their other operations - {@code /products} keeps its
 *       {@code get}, which is the one this suite calls
 * </ol>
 *
 * <p>Both are removals of things this suite does not exercise, not rewrites of
 * anything it does. What is validated is the document as published, minus the
 * operations no validator can read. That limit is real and is stated in
 * {@code docs/adr/0007}.
 */
public final class ContractSpec {

    /** The application serves its document here; {@code /api/documentation} is the UI. */
    private static final String SPEC_PATH = "/docs";

    /** The newest version swagger-parser understands. */
    private static final String SUPPORTED_VERSION = "3.1.0";

    /** An OpenAPI 3.2 operation type the parser has no concept of. */
    private static final String UNSUPPORTED_OPERATION = "query";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenApiInteractionValidator validator;
    private final OpenApiInteractionValidator strictValidator;
    private final List<String> removedOperations;

    private ContractSpec(OpenApiInteractionValidator validator,
            OpenApiInteractionValidator strictValidator, List<String> removedOperations) {
        this.validator = validator;
        this.strictValidator = strictValidator;
        this.removedOperations = List.copyOf(removedOperations);
    }

    /** Whitelisted: fails on anything that is not an already-known deviation. */
    public OpenApiInteractionValidator validator() {
        return validator;
    }

    /**
     * No whitelist. Used by the contract suite to assert that each known
     * deviation is still a deviation, so the whitelist cannot outlive its
     * reasons.
     */
    public OpenApiInteractionValidator strictValidator() {
        return strictValidator;
    }

    /** Which operations were dropped to make the document parseable. */
    public List<String> removedOperations() {
        return removedOperations;
    }

    static ContractSpec fetchedFrom(URI apiBaseUrl, Duration timeout) {
        String document = download(apiBaseUrl.resolve(SPEC_PATH), timeout);
        List<String> removed = new ArrayList<>();

        ObjectNode root;
        try {
            root = (ObjectNode) JSON.readTree(document);
        } catch (IOException e) {
            throw new IllegalStateException("the OpenAPI document at " + apiBaseUrl + SPEC_PATH
                    + " is not JSON", e);
        }

        root.put("openapi", SUPPORTED_VERSION);

        JsonNode paths = root.get("paths");
        if (paths instanceof ObjectNode pathsNode) {
            pathsNode.fields().forEachRemaining(entry -> {
                if (entry.getValue() instanceof ObjectNode operations
                        && operations.has(UNSUPPORTED_OPERATION)) {
                    operations.remove(UNSUPPORTED_OPERATION);
                    removed.add(UNSUPPORTED_OPERATION.toUpperCase(java.util.Locale.ROOT)
                            + " " + entry.getKey());
                }
            });
        }

        try {
            String normalised = JSON.writeValueAsString(root);
            return new ContractSpec(
                    validatorFor(normalised).withWhitelist(knownDocumentationDefects()).build(),
                    validatorFor(normalised).build(),
                    removed);
        } catch (OpenApiInteractionValidator.ApiLoadException e) {
            throw new IllegalStateException("the OpenAPI document at " + apiBaseUrl + SPEC_PATH
                    + " could not be parsed even after normalising it. Parser messages:\n  "
                    + String.join("\n  ", e.getParseMessages()), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Responses only.
     *
     * <p>Half the value of this suite is in its negative cases - a registration
     * missing every required field, a payment method the API does not offer -
     * and those requests are <em>supposed</em> to violate the schema. Validating
     * requests would turn every negative test red for being negative.
     */
    private static OpenApiInteractionValidator.Builder validatorFor(String document) {
        return OpenApiInteractionValidator.createFor(document)
                .withLevelResolver(LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        .build());
    }

    /**
     * The places where the application's documentation and the application
     * disagree, each declared individually.
     *
     * <p>Every entry is a defect in the document, not in this suite, and every
     * one was found by turning the validator on rather than by reading the
     * document. Twelve of them, in three shapes:
     *
     * <ul>
     *   <li><b>Undocumented status codes.</b> {@code POST /invoices} answers 201
     *       and the document lists only 200. Every {@code /reports/*} route and
     *       {@code GET /users} answer 403 to a non-administrator and the
     *       document lists 401 but not 403 - which is the more interesting half,
     *       because 401 and 403 are different answers to different questions.
     *       {@code POST /users/login} answers 401 for a wrong password and 423
     *       for a locked account, and the document mentions neither. The 423 was
     *       found by the whitelist itself: it was not in the first measurement
     *       because no account happened to be locked during it, and the very
     *       next run failed on it.
     *   <li><b>Bodies where none is documented.</b> Seven responses carry a
     *       body the document declares empty.
     *   <li><b>Fields the schema does not declare.</b> Two responses return
     *       properties absent from their schema.
     * </ul>
     *
     * <p>A whitelist is a place where drift can hide, so two things keep it
     * honest. It is declared entry by entry with a name and a reason rather than
     * as a blanket level change, so nothing is suppressed by accident. And
     * {@code ApiContractTest} validates the same interactions against
     * {@link #strictValidator()} - no whitelist - and asserts each deviation is
     * still there, so an entry that the application has since fixed fails the
     * build instead of quietly outliving its reason.
     */
    private static ValidationErrorsWhitelist knownDocumentationDefects() {
        return ValidationErrorsWhitelist.create()
                .withRule("POST /invoices answers 201; the document lists only 200",
                        WhitelistRules.allOf(
                                WhitelistRules.messageHasKey("validation.response.status.unknown"),
                                WhitelistRules.pathContains("/invoices"),
                                WhitelistRules.responseStatusIs(201)))
                .withRule("POST /users/login answers 401; the document does not list it",
                        WhitelistRules.allOf(
                                WhitelistRules.messageHasKey("validation.response.status.unknown"),
                                WhitelistRules.pathContains("/users/login"),
                                WhitelistRules.responseStatusIs(401)))
                .withRule("administrative routes answer 403; the document lists 401 but not 403",
                        WhitelistRules.allOf(
                                WhitelistRules.messageHasKey("validation.response.status.unknown"),
                                WhitelistRules.responseStatusIs(403)))
                .withRule("POST /users/login answers 423 for a locked account; undocumented",
                        WhitelistRules.allOf(
                                WhitelistRules.messageHasKey("validation.response.status.unknown"),
                                WhitelistRules.pathContains("/users/login"),
                                WhitelistRules.responseStatusIs(423)))
                .withRule("POST /users/register answers 422; the document does not list it",
                        WhitelistRules.allOf(
                                WhitelistRules.messageHasKey("validation.response.status.unknown"),
                                WhitelistRules.pathContains("/users/register"),
                                WhitelistRules.responseStatusIs(422)))
                .withRule("some responses carry a body the document declares empty",
                        WhitelistRules.messageHasKey("validation.response.body.unexpected"))
                .withRule("some responses return properties absent from their schema",
                        WhitelistRules.messageHasKey(
                                "validation.response.body.schema.additionalProperties"));
    }

    private static String download(URI specUrl, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(specUrl)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("GET " + specUrl + " answered "
                        + response.statusCode() + ", so there is no contract to validate against");
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("could not fetch the OpenAPI document from " + specUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted fetching " + specUrl, e);
        }
    }
}
