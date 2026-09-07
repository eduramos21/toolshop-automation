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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.tags.Admin;
import toolshop.automation.core.tags.Api;
import toolshop.automation.core.tags.Regression;
import toolshop.automation.core.tags.Smoke;

/**
 * Who may reach the administrative routes.
 *
 * <p>The half usually missing is the negative one. A test that an administrator
 * can read a report proves the route works; only a test that a customer cannot
 * proves it is protected, and that is the assertion an authorisation regression
 * breaks.
 *
 * <p>It also distinguishes 401 from 403 deliberately. Both are "no", but a 403
 * for an anonymous caller would mean the application had decided something about
 * a caller it has not identified.
 */
@Api
@Admin
@Epic("Administration")
@Feature("Route authorisation")
class AdminAuthorisationApiTest {

    private final ToolshopApi api;
    private final ToolshopConfig config;

    AdminAuthorisationApiTest(ToolshopApi api, ToolshopConfig config) {
        this.api = api;
        this.config = config;
    }

    @Test
    @Smoke
    @Story("A customer cannot list all users")
    @Severity(SeverityLevel.BLOCKER)
    void aCustomerCannotListAllUsers() {
        // Given an authenticated, non-administrative customer
        // When they ask for every user
        Response response = api.as(config.customer()).get("/users");

        // Then they are forbidden - identified, and refused
        assertThatStatus(response).isEqualTo(403);
    }

    @Test
    @Story("An administrator can list all users")
    void anAdministratorCanListAllUsers() {
        Response response = api.as(config.admin()).get("/users");

        assertThatStatus(response).isEqualTo(200);
        assertThat(response.jsonPath().getList("data")).isNotEmpty();
    }

    @Test
    @Story("Listing users anonymously is unauthorised, not forbidden")
    void listingUsersAnonymouslyIsUnauthorisedNotForbidden() {
        Response response = api.anonymous().get("/users");

        assertThatStatus(response)
                .as("401 identifies the problem as missing credentials, 403 as insufficient ones")
                .isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "average-sales-per-month",
            "average-sales-per-week",
            "customers-by-country",
            "top10-best-selling-categories",
            "top10-purchased-products",
            "total-sales-of-years",
            "total-sales-per-country",
    })
    @Regression
    @Story("Every report route is administrator-only")
    @Severity(SeverityLevel.CRITICAL)
    void everyReportRouteIsAdministratorOnly(String report) {
        String path = "/reports/" + report;

        // Given the three kinds of caller
        Response anonymous = api.anonymous().get(path);
        Response customer = api.as(config.customer()).get(path);
        Response administrator = api.as(config.admin()).get(path);

        // Then only the administrator gets through, and the other two are
        // refused for the correct, different reasons
        assertThatStatus(anonymous).as("%s anonymously", path).isEqualTo(401);
        assertThatStatus(customer).as("%s as a customer", path).isEqualTo(403);
        assertThatStatus(administrator).as("%s as an administrator", path).isEqualTo(200);
    }

    @Test
    @Story("A customer cannot delete a user")
    @Severity(SeverityLevel.BLOCKER)
    void aCustomerCannotDeleteAUser() {
        // Given a customer and the id of a real user - their own, so that a
        // failure here cannot be blamed on the id
        String ownId = api.as(config.customer()).get("/users/me").jsonPath().getString("id");

        // When they try to delete it
        Response response = api.as(config.customer()).delete("/users/" + ownId);

        // Then they are forbidden. Deletion is an administrative act even on
        // one's own account.
        assertThatStatus(response).isEqualTo(403);

        // And the account is still there
        assertThatStatus(api.as(config.admin()).get("/users/" + ownId)).isEqualTo(200);
    }
}
