package toolshop.automation.api.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /users/register}.
 *
 * <p>The password must survive the application's {@code uncompromised} rule,
 * which checks it against a breached-password service. Any fixed literal will
 * eventually fail that check, so registration test data uses a random one - see
 * {@link toolshop.automation.api.ToolshopApi#registerCustomer}.
 */
public record RegisterRequest(
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        Address address,
        String phone,
        String dob,
        String password,
        String email) {
}
