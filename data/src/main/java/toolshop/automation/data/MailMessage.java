package toolshop.automation.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;

/**
 * One message held by the mail catcher.
 *
 * <p>Recipients arrive wrapped in angle brackets - {@code <someone@example.test>}
 * - which is correct for the wire format and useless for comparing against an
 * address a test holds. {@link #wasSentTo(String)} does the unwrapping in one
 * place rather than every caller remembering to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MailMessage(
        int id,
        String sender,
        List<String> recipients,
        String subject,
        @JsonProperty("created_at") String createdAt) {

    public boolean wasSentTo(String address) {
        return recipients.stream().anyMatch(recipient -> unwrap(recipient).equalsIgnoreCase(address));
    }

    /** {@code <a@b.test>} to {@code a@b.test}; anything else is returned as-is. */
    static String unwrap(String recipient) {
        String trimmed = recipient == null ? "" : recipient.trim();
        return trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() >= 2
                ? trimmed.substring(1, trimmed.length() - 1).trim()
                : trimmed;
    }

    public boolean hasSubject(String expected) {
        return subject != null && subject.toLowerCase(Locale.ROOT).equals(expected.toLowerCase(Locale.ROOT));
    }
}
