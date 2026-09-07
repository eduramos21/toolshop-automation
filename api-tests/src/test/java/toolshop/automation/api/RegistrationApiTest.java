package toolshop.automation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static toolshop.automation.api.ApiAssertions.assertThatStatus;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toolshop.automation.api.payload.Address;
import toolshop.automation.api.payload.LoginRequest;
import toolshop.automation.api.payload.RegisterRequest;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.testdata.TestDataRegistry;
import toolshop.automation.core.tags.Api;
import toolshop.automation.core.tags.Auth;
import toolshop.automation.core.tags.Smoke;

@Api
@Auth
@Epic("Accounts")
@Feature("Registration")
class RegistrationApiTest {

    private final ToolshopApi api;
    private final ToolshopConfig config;

    RegistrationApiTest(ToolshopApi api, ToolshopConfig config) {
        this.api = api;
        this.config = config;
    }

    @Test
    @Smoke
    @Story("A new customer can register and then log in")
    @Severity(SeverityLevel.CRITICAL)
    void registeringANewCustomerCreatesAnAccountThatCanLogIn(TestDataRegistry data) {
        // Given a customer who does not exist yet, at an address the application
        // itself considers valid
        Address address = Address.from(api.addressFor("NL", "1011AB"), "Test Street", "1");
        RegisterRequest request = api.newCustomer(address);

        // When they register
        Response registration = api.registerCustomer(request);

        // Then the account exists
        assertThatStatus(registration).isEqualTo(201);

        String createdUserId = registration.jsonPath().getString("id");
        // Registered for removal the moment it exists, so it is cleaned up even
        // if an assertion below fails.
        data.deleteAfterwards("customer " + request.email(),
                () -> api.requireCustomerDeleted(createdUserId));

        assertThat(createdUserId).isNotBlank();
        assertThat(registration.jsonPath().getString("email")).isEqualTo(request.email());

        // And it can be used
        Response login = api.anonymous()
                .body(new LoginRequest(request.email(), request.password()))
                .post("/users/login");
        assertThatStatus(login).isEqualTo(200);
        assertThat(login.jsonPath().getString("access_token")).isNotBlank();
    }

    @Test
    @Story("An email that is already registered is rejected as a conflict")
    void registeringAnEmailThatAlreadyExistsIsRejected() {
        // Given an address the application accepts, and an already-seeded customer
        Address address = Address.from(api.addressFor("NL", "1011AB"), "Test Street", "1");
        RegisterRequest duplicate = new RegisterRequest("Test", "Person", address, "0987654321",
                "1990-01-01", "Ts" + java.util.UUID.randomUUID() + "!7", config.customer().email());

        // When that email is registered again
        Response response = api.registerCustomer(duplicate);

        // Then it is a conflict, not a validation error
        assertThatStatus(response).isEqualTo(409);
        assertThat(response.asString()).contains("email");
    }

    @Test
    @Story("An empty registration names every missing field")
    void registeringWithNoPayloadNamesEveryMissingField() {
        // Given nothing but an email
        // When it is submitted
        Response response = api.anonymous().body(Map.of("email", "nobody@example.test"))
                .post("/users/register");

        // Then every missing required field is reported, not just the first
        assertThatStatus(response).isEqualTo(422);
        assertThat(response.asString())
                .as("all missing fields should be reported together")
                .contains("first_name", "last_name", "password");
    }
}
