package toolshop.automation.api.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The address block inside {@code POST /users/register}. */
public record Address(
        String street,
        @JsonProperty("house_number") String houseNumber,
        String city,
        String state,
        String country,
        @JsonProperty("postal_code") String postalCode) {

    /**
     * An address the application will accept, built from its own postcode
     * lookup. Note {@code postcode} becomes {@code postal_code} here: the two
     * endpoints disagree on the name for the same value.
     */
    public static Address from(PostcodeLookup lookup, String street, String houseNumber) {
        return new Address(street, houseNumber, lookup.city(), lookup.state(),
                lookup.country(), lookup.postcode());
    }
}
