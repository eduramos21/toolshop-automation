package toolshop.automation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static toolshop.automation.api.ApiAssertions.assertThatStatus;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import toolshop.automation.api.ToolshopApi.DisposableCustomer;
import toolshop.automation.api.payload.AuthToken;
import toolshop.automation.api.payload.LoginRequest;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.testdata.TestDataRegistry;
import toolshop.automation.core.tags.Api;
import toolshop.automation.core.tags.Auth;
import toolshop.automation.core.tags.Regression;
import toolshop.automation.core.tags.Smoke;

/**
 * Login, and the paths that must refuse it.
 *
 * <p>Every test that deliberately fails a login uses an account it created for
 * the purpose. The application locks a non-administrative account after three
 * failed attempts and a correct password does not clear it, so failing a login
 * against the shared seeded customer poisons every subsequent run - and under
 * method-level parallelism it does so intermittently, which is worse. See
 * {@link ToolshopApi#createDisposableCustomer}.
 */
@Api
@Auth
@Epic("Accounts")
@Feature("Login")
class AuthenticationApiTest {

    private final ToolshopApi api;
    private final ToolshopConfig config;

    AuthenticationApiTest(ToolshopApi api, ToolshopConfig config) {
        this.api = api;
        this.config = config;
    }

    @Test
    @Smoke
    @Story("A seeded customer can obtain a bearer token")
    @Severity(SeverityLevel.BLOCKER)
    void loginReturnsABearerTokenForASeededCustomer() {
        // Given the seeded customer credentials, supplied from the environment
        LoginRequest request = new LoginRequest(config.customer().email(), config.customer().password());

        // When they log in
        Response response = api.anonymous().body(request).post("/users/login");

        // Then a usable bearer token comes back
        assertThatStatus(response).isEqualTo(200);

        AuthToken token = response.as(AuthToken.class);
        assertThat(token.accessToken())
                .as("the field is access_token, not token")
                .isNotBlank();
        assertThat(token.tokenType()).isEqualToIgnoringCase("bearer");
        assertThat(token.expiresInSeconds())
                .as("token lifetime, which the client's renewal margin depends on")
                .isPositive();
    }

    @Test
    @Story("A wrong password is rejected")
    @Severity(SeverityLevel.CRITICAL)
    void loginWithAWrongPasswordIsRejected(TestDataRegistry data) {
        // Given a disposable account, because this attempt increments its
        // failed-login counter
        DisposableCustomer throwaway = api.createDisposableCustomer(data);

        // When it is used with the wrong password
        Response response = login(throwaway.credentials().email(), "not-the-password");

        // Then it is refused
        assertThatStatus(response).isEqualTo(401);
    }

    /**
     * The assertion worth having is not that both fail - it is that they fail
     * <em>identically</em>. A different status or message for an unknown email
     * than for a wrong password tells an attacker which accounts exist.
     */
    @Test
    @Story("An unknown email is rejected exactly as a wrong password is")
    @Severity(SeverityLevel.CRITICAL)
    void loginRevealsNothingAboutWhichAccountsExist(TestDataRegistry data) {
        // Given one real account given the wrong password, and one that does not exist
        DisposableCustomer throwaway = api.createDisposableCustomer(data);

        Response wrongPassword = login(throwaway.credentials().email(), "not-the-password");
        Response unknownAccount = login("no-such-account@example.test", "not-the-password");

        // Then neither response distinguishes the two cases
        assertThatStatus(wrongPassword).isEqualTo(401);
        assertThat(unknownAccount.statusCode())
                .as("an unknown email must not be distinguishable from a wrong password")
                .isEqualTo(wrongPassword.statusCode());
        assertThat(unknownAccount.asString()).isEqualTo(wrongPassword.asString());
    }

    /**
     * The lockout itself, which is only safely testable against an account
     * nobody else uses. It also pins down the part that makes the shared-account
     * version so damaging: a correct password does not recover a locked account.
     */
    @Test
    @Regression
    @Story("An account locks after three failed attempts and a correct password does not recover it")
    @Severity(SeverityLevel.CRITICAL)
    void anAccountLocksAfterThreeFailedAttemptsAndStaysLocked(TestDataRegistry data) {
        // Given a disposable account
        DisposableCustomer throwaway = api.createDisposableCustomer(data);
        String email = throwaway.credentials().email();

        // When three logins fail
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThatStatus(login(email, "not-the-password-" + attempt))
                    .as("failed attempt %d should still be a plain rejection", attempt)
                    .isEqualTo(401);
        }

        // Then the account is locked, and the correct password does not help
        Response withTheRightPassword = login(email, throwaway.credentials().password());
        assertThatStatus(withTheRightPassword)
                .as("423 Locked, not 200 - the lock is checked before the password")
                .isEqualTo(423);
    }

    @Test
    @Story("An empty login request is rejected")
    void loginWithNoCredentialsIsRejected() {
        // Given nothing at all - no account is touched, so no counter moves
        Response response = login("", "");

        assertThatStatus(response).isEqualTo(401);
    }

    @Test
    @Story("The current-user route requires a token")
    @Severity(SeverityLevel.CRITICAL)
    void theCurrentUserRouteRequiresAToken() {
        // Given no credentials
        // When the current user is requested
        Response response = api.anonymous().get("/users/me");

        // Then it is unauthorised - not forbidden, which would imply a known caller
        assertThatStatus(response).isEqualTo(401);
    }

    @Test
    @Smoke
    @Story("The current-user route returns the authenticated customer")
    void theCurrentUserRouteReturnsTheAuthenticatedCustomer() {
        // Given an authenticated customer
        // When they ask who they are
        Response response = api.as(config.customer()).get("/users/me");

        // Then it is them
        assertThatStatus(response).isEqualTo(200);
        assertThat(response.jsonPath().getString("email")).isEqualTo(config.customer().email());
        assertThat(response.jsonPath().getString("id")).isNotBlank();
    }

    private Response login(String email, String password) {
        return api.anonymous().body(new LoginRequest(email, password)).post("/users/login");
    }
}
