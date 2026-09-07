package toolshop.automation.api;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import toolshop.automation.api.payload.Address;
import toolshop.automation.api.payload.AuthToken;
import toolshop.automation.api.payload.CartItem;
import toolshop.automation.api.payload.LoginRequest;
import toolshop.automation.api.payload.PaymentMethod;
import toolshop.automation.api.payload.PostcodeLookup;
import toolshop.automation.api.payload.RegisterRequest;
import toolshop.automation.core.config.ToolshopConfig;

/**
 * The API client: a request specification, an authentication token, and the few
 * read operations a test needs as setup rather than as its subject.
 *
 * <p>It deliberately owns no assertions. A helper that asserts cannot be reused
 * by a test expecting a different outcome, and the negative paths - 401, 403,
 * 404, 422 - are half of what is worth testing here. So every method either
 * returns a {@link Response} for the test to judge, or returns a value and
 * throws if the setup it was asked to perform is impossible.
 *
 * <p>One instance per test JVM, supplied to test constructors by
 * {@link ToolshopApiExtension}.
 *
 * <p>Thread safety: the base specification is used as a template through
 * {@code .spec(...)}, which copies into a fresh specification per call. That is
 * REST Assured's documented pattern for sharing a spec; sharing a single
 * {@code RequestSpecification} object across threads is not safe, and nothing
 * here does. No REST Assured static is ever written to.
 */
public final class ToolshopApi {

    /**
     * How long before expiry a cached token is replaced. Measured lifetime is
     * 300 seconds, so a request that begins just under the wire would otherwise
     * be able to arrive just over it.
     */
    private static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);

    private final ToolshopConfig config;
    private final RequestSpecification template;
    private final ConcurrentMap<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public ToolshopApi(ToolshopConfig config) {
        this.config = config;
        int timeoutMillis = Math.toIntExact(config.apiTimeout().toMillis());
        this.template = new RequestSpecBuilder()
                .setBaseUri(config.apiBaseUrl().toString())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                // Set on the specification rather than on RestAssured's statics,
                // so nothing here depends on global state another test could
                // have changed.
                .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", timeoutMillis)
                        .setParam("http.socket.timeout", timeoutMillis)))
                .build();
    }

    public ToolshopConfig config() {
        return config;
    }

    /** A request with no credentials. */
    public RequestSpecification anonymous() {
        return RestAssured.given().spec(template);
    }

    /** A request authenticated as the given account, logging in only if needed. */
    public RequestSpecification as(ToolshopConfig.Credentials who) {
        return anonymous().header("Authorization", "Bearer " + tokenFor(who));
    }

    /**
     * A bearer token for the account, cached until shortly before it expires.
     *
     * <p>Keyed by account rather than by thread. A token is a property of the
     * account, not of the caller, so a per-thread cache would multiply logins by
     * the parallelism for no gain. {@code compute} holds the map's bin lock
     * across the login, which serialises concurrent first-time callers for the
     * same account onto one request - the intended behaviour, not a cost.
     */
    private String tokenFor(ToolshopConfig.Credentials who) {
        return tokens.compute(who.email(), (email, cached) ->
                cached != null && cached.isUsable() ? cached : login(who)).value();
    }

    private CachedToken login(ToolshopConfig.Credentials who) {
        Response response = anonymous()
                .body(new LoginRequest(who.email(), who.password()))
                .post("/users/login");

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "could not log in as " + who.email() + " against " + config.apiBaseUrl()
                    + ": HTTP " + response.statusCode() + " " + response.asString()
                    + ". The password comes from the environment - see docs/CONFIGURATION.md.");
        }

        AuthToken token = response.as(AuthToken.class);
        return new CachedToken(token.accessToken(), Instant.now().plus(token.lifetime()));
    }

    // ------------------------------------------------------------------ setup

    /**
     * The address the application itself considers valid for a country and
     * postcode.
     *
     * <p>{@code POST /invoices} validates the billing city and state against
     * this lookup, so an invoice test asks for an address rather than asserting
     * one into existence.
     */
    public PostcodeLookup addressFor(String country, String postcode) {
        Response response = anonymous()
                .queryParam("country", country)
                .queryParam("postcode", postcode)
                .get("/postcode-lookup");

        if (response.statusCode() != 200) {
            throw new IllegalStateException("postcode lookup failed for " + country + "/" + postcode
                    + ": HTTP " + response.statusCode() + " " + response.asString());
        }
        return response.as(PostcodeLookup.class);
    }

    /** The id of some product that is in stock, for tests that need any product. */
    public String someProductInStock() {
        Response response = anonymous().get("/products");
        List<String> ids = response.jsonPath()
                .getList("data.findAll { it.in_stock == true }.id", String.class);

        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("no product is in stock at " + config.apiBaseUrl()
                    + ". Seed the application: docker compose exec laravel-api"
                    + " php artisan migrate:fresh --seed");
        }
        return ids.get(0);
    }

    /** A cart with one item in it. Returns the cart id. */
    public String cartContaining(String productId, int quantity) {
        Response created = anonymous().post("/carts");
        if (created.statusCode() != 201) {
            throw new IllegalStateException("could not create a cart: HTTP " + created.statusCode()
                    + " " + created.asString());
        }
        String cartId = created.path("id");

        Response added = anonymous().body(new CartItem(productId, quantity)).post("/carts/" + cartId);
        if (added.statusCode() != 200) {
            throw new IllegalStateException("could not add " + productId + " to cart " + cartId
                    + ": HTTP " + added.statusCode() + " " + added.asString());
        }
        return cartId;
    }

    /**
     * A registration payload for an account that does not exist yet.
     *
     * <p>The email is unique per call and carries a {@code toolshop-} prefix, so
     * anything left behind by an interrupted run is identifiable. The password is
     * random because the application rejects passwords found in a breach corpus,
     * which any fixed literal eventually is.
     */
    public RegisterRequest newCustomer(Address address) {
        String unique = UUID.randomUUID().toString();
        return new RegisterRequest("Test", "Person", address, "0987654321", "1990-01-01",
                "Ts" + unique + "!7", "toolshop-" + unique + "@example.test");
    }

    public Response registerCustomer(RegisterRequest request) {
        return anonymous().body(request).post("/users/register");
    }

    /**
     * An account created for one test to abuse and then delete.
     *
     * <p>This exists because of a measured hazard. The application locks a
     * non-administrative account after three failed login attempts, the check
     * runs <em>before</em> the password is examined, and a correct password does
     * not clear it - only a successful login resets the counter, and once locked
     * no login can succeed. So a test that deliberately fails a login against
     * the shared seeded customer damages every later run, and under method-level
     * parallelism it races the tests that log in successfully: sometimes three
     * failures land before a success resets the count, sometimes they do not.
     *
     * <p>The seeded accounts therefore only ever receive correct passwords.
     * Anything that must fail a login gets one of these. Administrators are
     * exempt from locking, which is why cleanup can still authenticate.
     */
    public DisposableCustomer createDisposableCustomer() {
        RegisterRequest request = newCustomer(
                Address.from(addressFor("NL", "1011AB"), "Test Street", "1"));
        Response response = registerCustomer(request);

        if (response.statusCode() != 201) {
            throw new IllegalStateException("could not create a disposable customer at "
                    + config.apiBaseUrl() + ": HTTP " + response.statusCode() + " "
                    + response.asString());
        }
        return new DisposableCustomer(response.jsonPath().getString("id"),
                new ToolshopConfig.Credentials(request.email(), request.password()));
    }

    /**
     * An account belonging to one test, which is responsible for deleting it.
     *
     * <p>P7 replaces the remembering-to-delete part with a registry and an
     * extension. Until then it is an {@code @AfterEach}, which is the part that
     * matters: it runs when the test fails, and a test that fails midway is
     * exactly the one that leaves something behind.
     */
    public record DisposableCustomer(String id, ToolshopConfig.Credentials credentials) {
    }

    /**
     * Removes an account created by a test. Requires administrative rights.
     *
     * <p>Returns the status so a caller can report a cleanup that did not happen
     * rather than hiding it. Deleting an id that does not exist answers 500 on
     * this application, so a caller should not use this to "make sure".
     */
    public int deleteCustomer(String userId) {
        return as(config.admin()).delete("/users/" + userId).statusCode();
    }

    /** Payment details the application accepts for the given method. */
    public static Map<String, Object> paymentDetailsFor(PaymentMethod method) {
        return switch (method) {
            case CREDIT_CARD -> Map.of(
                    "credit_card_number", "4111-1111-1111-1111",
                    "expiration_date", "12/2030",
                    "cvv", "123",
                    "card_holder_name", "Test Person");
            case BANK_TRANSFER -> Map.of(
                    "bank_name", "Test Bank",
                    "account_name", "Test Person",
                    "account_number", "1234567890");
            // Exactly 16 alphanumeric characters and a 4-character code. This is
            // the only payment method POST /invoices validates the details of.
            case GIFT_CARD -> Map.of(
                    "gift_card_number", "1234567890123456",
                    "validation_code", "1234");
            case BUY_NOW_PAY_LATER -> Map.of("monthly_installments", 3);
            case CASH_ON_DELIVERY -> Map.of();
        };
    }

    private record CachedToken(String value, Instant expiresAt) {

        boolean isUsable() {
            return Instant.now().isBefore(expiresAt.minus(RENEWAL_MARGIN));
        }
    }
}
