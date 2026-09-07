package toolshop.automation.api.payload;

/**
 * {@code POST /users/login}.
 *
 * <p>Both field names are single words, so no {@code @JsonProperty} is needed.
 * Most payloads here are not so lucky - the API is snake_case throughout.
 */
public record LoginRequest(String email, String password) {
}
