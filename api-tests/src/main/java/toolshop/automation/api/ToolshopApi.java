package toolshop.automation.api;

import io.restassured.RestAssured;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import toolshop.automation.api.payload.Address;
import toolshop.automation.api.payload.AuthToken;
import toolshop.automation.api.payload.CartItem;
import toolshop.automation.api.payload.LoginRequest;
import toolshop.automation.api.payload.PaymentMethod;
import toolshop.automation.api.payload.PostcodeLookup;
import toolshop.automation.api.payload.RegisterRequest;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.testdata.TestDataRegistry;

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

    /** The application's OpenAPI document, loaded once. */
    private final ContractSpec contract;

    public ToolshopApi(ToolshopConfig config) {
        this.config = config;
        this.contract = ContractSpec.fetchedFrom(config.apiBaseUrl(), config.apiTimeout());
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
                // Contract validation on the shared specification, so it is not
                // opt-in. A test cannot forget it and a new test gets it for
                // free - which is the difference between a contract that is
                // checked and a contract that has a test somewhere.
                .addFilter(new OpenApiValidationFilter(contract.validator()))
                .build();
    }

    public ToolshopConfig config() {
        return config;
    }

    /** The loaded contract, for the tests that assert on the contract itself. */
    public ContractSpec contract() {
        return contract;
    }

    /**
     * A request validated against the document with <em>no</em> whitelist.
     *
     * <p>Used only by the contract suite, to assert that each whitelisted
     * deviation is still a deviation. Everything else goes through
     * {@link #anonymous()}, which whitelists the known ones and fails on
     * anything new.
     */
    public RequestSpecification strictlyValidated() {
        int timeoutMillis = Math.toIntExact(config.apiTimeout().toMillis());
        return RestAssured.given().spec(new RequestSpecBuilder()
                .setBaseUri(config.apiBaseUrl().toString())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", timeoutMillis)
                        .setParam("http.socket.timeout", timeoutMillis)))
                .addFilter(new OpenApiValidationFilter(contract.strictValidator()))
                .build());
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

    /**
     * Products the application caps at one per cart, so a test that buys several
     * of an arbitrary product never picks one.
     *
     * <p>By name, because that is how the application does it:
     * {@code CartService} compares {@code $product->name === 'Thor Hammer'} and
     * rejects any quantity above one. The cap is not in the product payload, so
     * there is nothing to detect it by - a framework that wants to avoid it has
     * to know about it.
     */
    private static final Set<String> LIMITED_TO_ONE_PER_CART = Set.of("Thor Hammer");

    /** A bound on the catalogue walk, so a misconfigured target cannot loop. */
    private static final int MAX_CATALOGUE_PAGES = 20;

    /**
     * Some product that is in stock and can be bought more than once.
     *
     * <p>Returns the name and price as well as the id, because a UI test needs
     * to search for the name and check the price the storefront renders, and
     * fetching those separately would be three calls for one fact.
     *
     * <p>Two things here are the result of getting it wrong first, and both are
     * about what "any product" has to mean.
     *
     * <p><b>It pages.</b> The first version read page one only. A checkout
     * reduces stock and the application does not guard the floor - stock reaches
     * negative numbers - so after a couple of full runs the first page was eight
     * out-of-stock products and one that could not be bought twice, and the
     * suite had quietly consumed its own test data.
     *
     * <p><b>It picks at random rather than first.</b> Always taking the first
     * candidate concentrates every purchase in a run on one product: roughly
     * fifteen units per full run against a seeded stock of twenty-five, so two
     * runs exhaust it. Spreading across the catalogue makes depletion a
     * non-issue. The cost is that the product varies between runs, which is why
     * every assertion that uses it names it in its failure message.
     *
     * <p>Stock is still consumed - that is what buying is - and only
     * {@code ./run up} restores it. What is fixed is a suite that could no longer
     * run because of what it had bought.
     */
    public Product anyProductInStock() {
        int lastPage = 1;

        for (int page = 1; page <= lastPage && page <= MAX_CATALOGUE_PAGES; page++) {
            Response response = anonymous().queryParam("page", page).get("/products");
            if (response.statusCode() != 200) {
                throw new IllegalStateException("could not read the catalogue at "
                        + config.apiBaseUrl() + ": HTTP " + response.statusCode());
            }
            lastPage = response.jsonPath().getInt("last_page");

            List<Map<String, Object>> candidates = response.jsonPath()
                    .getList("data.findAll { it.in_stock == true }");
            List<Map<String, Object>> buyable = candidates == null ? List.of() : candidates.stream()
                    .filter(candidate ->
                            !LIMITED_TO_ONE_PER_CART.contains(String.valueOf(candidate.get("name"))))
                    .toList();

            if (!buyable.isEmpty()) {
                Map<String, Object> product =
                        buyable.get(ThreadLocalRandom.current().nextInt(buyable.size()));
                return new Product(
                        String.valueOf(product.get("id")),
                        String.valueOf(product.get("name")),
                        Double.parseDouble(String.valueOf(product.get("price"))));
            }
        }

        throw new IllegalStateException("no product at " + config.apiBaseUrl()
                + " is both in stock and free of a per-cart limit, across " + lastPage
                + " page(s) of the catalogue.\\nBuying reduces stock and nothing restores it"
                + " automatically. Reset the application data:\\n  ./run up");
    }

    /** Just enough of a product for a test to act on it. */
    public record Product(String id, String name, double price) {
    }

    /**
     * A search term the application's own search matches, with the products it
     * returns for it.
     *
     * <p>Derived from the application rather than chosen, because the search is
     * not a substring match and a reasonable-looking term returns nothing.
     * {@code /products/search} runs
     * {@code MATCH(name) AGAINST(? IN BOOLEAN MODE)}, which requires every word:
     * searching a product's own full name - "Claw Hammer with Shock Reduction
     * Grip" - returns zero rows, because "with" is a MySQL stopword. The first
     * two words of the same name return three.
     *
     * <p>Taking the term and the expected ids from here keeps the UI test about
     * the UI: does the storefront render what the search endpoint returned. It
     * is not a test of the ranking.
     */
    public Search aSearchThatMatches() {
        String name = anyProductInStock().name();
        String[] words = name.split("\\s+");
        String term = words.length >= 2 ? words[0] + " " + words[1] : words[0];

        Response response = anonymous().queryParam("q", term).get("/products/search");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("product search failed for '" + term + "': HTTP "
                    + response.statusCode() + " " + response.asString());
        }
        List<String> ids = response.jsonPath().getList("data.id", String.class);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("the application's own search returns nothing for '"
                    + term + "', taken from the product name '" + name + "'");
        }
        return new Search(term, ids);
    }

    /** A term and the product ids the application returns for it. */
    public record Search(String term, List<String> productIds) {
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
     *
     * <p>The registry is a required argument, not a convenience. It is the only
     * way to create one of these, so a test cannot create an account without
     * also saying how it is removed - and the removal then runs in
     * {@code afterEach}, including when the test fails.
     */
    public DisposableCustomer createDisposableCustomer(TestDataRegistry registry) {
        RegisterRequest request = newCustomer(
                Address.from(addressFor("NL", "1011AB"), "Test Street", "1"));
        Response response = registerCustomer(request);

        if (response.statusCode() != 201) {
            throw new IllegalStateException("could not create a disposable customer at "
                    + config.apiBaseUrl() + ": HTTP " + response.statusCode() + " "
                    + response.asString());
        }
        String id = response.jsonPath().getString("id");
        registry.deleteAfterwards("customer " + request.email(), () -> requireCustomerDeleted(id));
        return new DisposableCustomer(id,
                new ToolshopConfig.Credentials(request.email(), request.password()));
    }

    /**
     * Removes an account and insists that it worked.
     *
     * <p>Registered as the cleanup for every disposable customer, so a delete
     * that silently failed becomes a failing test rather than a row the next run
     * inherits.
     */
    public void requireCustomerDeleted(String userId) {
        int status = deleteCustomer(userId);
        if (status != 204) {
            throw new IllegalStateException(
                    "deleting user " + userId + " answered HTTP " + status + ", expected 204");
        }
    }

    /** An account belonging to one test, removed for it by the registry. */
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
