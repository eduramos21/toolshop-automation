package toolshop.automation.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The one piece of logic in the mail layer that is not a straight HTTP call:
 * recipients come back wrapped in angle brackets, and a test holds a plain
 * address.
 */
class MailMessageTest {

    @ParameterizedTest
    @CsvSource({
            "'<someone@example.test>', someone@example.test",
            "'someone@example.test',   someone@example.test",
            "'  <someone@example.test>  ', someone@example.test",
            "'<>',                     ''",
    })
    void unwrapsTheWireFormOfAnAddress(String wire, String expected) {
        assertThat(MailMessage.unwrap(wire)).isEqualTo(expected);
    }

    @Test
    void matchesARecipientRegardlessOfWrappingOrCase() {
        MailMessage message = new MailMessage(1, "<shop@example.test>",
                List.of("<Buyer@Example.Test>"), "Checkout", "2026-09-07T10:00:00+00:00");

        assertThat(message.wasSentTo("buyer@example.test")).isTrue();
        assertThat(message.wasSentTo("someone-else@example.test")).isFalse();
    }

    @Test
    void doesNotMatchAnAddressThatIsMerelyASubstring() {
        MailMessage message = new MailMessage(1, "<shop@example.test>",
                List.of("<buyer@example.test>"), "Checkout", "2026-09-07T10:00:00+00:00");

        assertThat(message.wasSentTo("uyer@example.test")).isFalse();
    }
}
