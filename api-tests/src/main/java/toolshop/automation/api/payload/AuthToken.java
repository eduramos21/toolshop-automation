package toolshop.automation.api.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;

/**
 * The response to {@code POST /users/login}.
 *
 * <p>The field is {@code access_token}. Not {@code token} - which is the name a
 * reasonable person guesses, and guessing it produces a null token and a 401 on
 * the next call rather than an error here.
 *
 * <p>{@code expires_in} is measured at 300 seconds against both the local and
 * hosted targets, which is short enough that a suite outliving a cached token is
 * an ordinary occurrence rather than an edge case. {@link
 * toolshop.automation.api.ToolshopApi} renews on that basis.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthToken(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresInSeconds) {

    public Duration lifetime() {
        return Duration.ofSeconds(expiresInSeconds);
    }
}
