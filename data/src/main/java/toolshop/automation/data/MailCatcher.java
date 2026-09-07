package toolshop.automation.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import toolshop.automation.core.config.ToolshopConfig;

/**
 * The mail the application actually sent.
 *
 * <p>An order confirmation is part of a checkout the way an invoice row is. A
 * test that stops at the success message has not checked that the customer was
 * told anything.
 *
 * <p>{@code java.net.http.HttpClient} rather than a client library: this is
 * three GETs against a local service, and the JDK has an HTTP client. Only the
 * JSON needs a library, and Jackson is already here for the API payloads.
 *
 * <p>Deliberately never calls {@code DELETE /messages}. Clearing the mailbox is
 * the obvious way to find "the" message and it makes the tests mutually
 * destructive under parallel execution - one test's clear deletes another's
 * evidence. Tests buy as an account whose address is unique to them instead, so
 * the message is identifiable without emptying anything.
 */
public final class MailCatcher {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Retry cadence for {@link #awaitLatestTo}, not a wait for anything. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final URI baseUrl;
    private final HttpClient http;
    private final Duration timeout;

    private MailCatcher(URI baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * The mail catcher for this run, or a failure explaining that this target
     * has none - for the same reason {@link ToolshopDatabase#from} does.
     */
    public static MailCatcher from(ToolshopConfig config) {
        URI base = config.mailBaseUrl().orElseThrow(() -> new IllegalStateException(
                "no mail catcher is configured for profile '" + config.profile() + "'.\n"
                + "Mail verification only applies to a target that has one, which is the local\n"
                + "and ci profiles. For a hosted run, exclude these tests:\n"
                + "  ./run test -Ptags='!db'\n"
                + "Note the mail catcher only exists when the application is started with its\n"
                + "docker-compose.override.yml - see docs/CONFIGURATION.md."));
        return new MailCatcher(base, config.apiTimeout());
    }

    public List<MailMessage> messages() {
        String body = get("/messages");
        try {
            return List.of(JSON.readValue(body, MailMessage[].class));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the mailbox at " + baseUrl + ": " + body, e);
        }
    }

    /** The newest message sent to an address, if there is one. */
    public Optional<MailMessage> latestTo(String recipient) {
        return messages().stream()
                .filter(message -> message.wasSentTo(recipient))
                .max(Comparator.comparingInt(MailMessage::id));
    }

    /** The newest message to an address with a given subject, if there is one. */
    public Optional<MailMessage> latestTo(String recipient, String subject) {
        return messages().stream()
                .filter(message -> message.wasSentTo(recipient) && message.hasSubject(subject))
                .max(Comparator.comparingInt(MailMessage::id));
    }

    /**
     * The newest message to an address, waiting for it to arrive if it has not.
     *
     * <p>Matched on subject as well as recipient. Registration also sends mail,
     * so "the newest message to this address" is the welcome email until the
     * confirmation arrives - and a poll that accepts it returns immediately with
     * the wrong message, which is worse than not polling at all.
     *
     * <p>The confirmation is a queued job: {@code SendCheckoutEmail implements
     * ShouldQueue}. The application's image ships {@code queue.default=database}
     * and runs nothing that drains the queue - php-fpm only in the API
     * container, an empty {@code /etc/periodic/15min} in cron - so one suite run
     * against the shipped configuration leaves fifteen rows in the {@code jobs}
     * table and mail arrives when it happens to arrive.
     *
     * <p>The pinned compose therefore sets {@code QUEUE_CONNECTION=sync}, which
     * sends during the request that raises the invoice. This poll remains
     * because there is still no push notification and the mailbox is read over
     * HTTP - but it is now waiting on a send that has already happened, not on a
     * worker that may never run.
     *
     * <p>So this polls, and the distinction from a fixed sleep matters. A fixed
     * sleep stands in for a condition and encodes a guess at how long something
     * takes: too short and it is flaky, too long and every run pays for it. This
     * is a retry cadence bounded by a deadline - the same shape as
     * {@code scripts/wait-for-sut.sh} - so it returns as soon as the condition
     * holds and fails with a clear message when it never does.
     */
    public MailMessage awaitMessage(String recipient, String subject, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        int attempts = 0;

        while (true) {
            attempts++;
            Optional<MailMessage> found = latestTo(recipient, subject);
            if (found.isPresent()) {
                return found.get();
            }
            if (System.nanoTime() >= deadline) {
                List<MailMessage> all = messages();
                String recent = all.stream()
                        .skip(Math.max(0, all.size() - 5))
                        .map(message -> message.id() + " '" + message.subject() + "' to "
                                + message.recipients())
                        .collect(java.util.stream.Collectors.joining("\n  "));
                throw new AssertionError("no '" + subject + "' mail reached " + recipient
                        + " within " + budget + " (" + attempts + " attempts)."
                        + "\nMail for that address: " + latestTo(recipient)
                                .map(MailMessage::subject).orElse("none")
                        + "\nMailbox holds " + all.size() + " message(s); the last few:\n  "
                        + recent);
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for mail to " + recipient, e);
            }
        }
    }

    /** The plain-text body, which is where the order lines and the total are. */
    public String plainBodyOf(MailMessage message) {
        return get("/messages/" + message.id() + ".plain");
    }

    private String get(String path) {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GET " + path + " on the mail catcher answered "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("the mail catcher at " + baseUrl
                    + " did not answer. Is it running? ./run status", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading the mail catcher", e);
        }
    }
}
