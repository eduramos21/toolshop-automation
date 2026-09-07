package toolshop.automation.api.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The response to {@code GET /postcode-lookup?country=&postcode=}.
 *
 * <p>This endpoint is the application's own source of truth for which city and
 * state belong to a country and postcode, and {@code POST /invoices} rejects a
 * billing address that disagrees with it. So a test that needs a valid address
 * asks for one rather than hardcoding a city.
 *
 * <p>That is not defensiveness about a quirk - it is the difference between a
 * test that encodes an assumption about the data and one that does not. The
 * local driver generates locality data from the postcode, so the "correct" city
 * for {@code NL/1011AB} is a fixture, not a fact about the Netherlands.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostcodeLookup(
        String street,
        @JsonProperty("house_number") String houseNumber,
        String city,
        String state,
        String country,
        String postcode) {
}
