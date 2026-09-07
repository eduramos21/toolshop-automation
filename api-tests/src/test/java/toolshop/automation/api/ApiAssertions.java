package toolshop.automation.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.assertj.core.api.AbstractIntegerAssert;

/**
 * One helper, for one reason: a failed status assertion is useless without the
 * body that came with it.
 *
 * <p>REST Assured's own {@code .then().statusCode(200)} would do this, and is
 * not used anywhere here. AssertJ is the only assertion vocabulary in this
 * repository, so there is one failure format and one set of diagnostics rather
 * than two that look different in the same report.
 */
final class ApiAssertions {

    private ApiAssertions() {
    }

    static AbstractIntegerAssert<?> assertThatStatus(Response response) {
        return assertThat(response.statusCode())
                .as("HTTP status; the response body was: %s", response.asString());
    }
}
