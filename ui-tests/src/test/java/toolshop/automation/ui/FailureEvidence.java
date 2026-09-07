package toolshop.automation.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.impl.junit.PageExtension;
import io.qameta.allure.Allure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Attaches the evidence for a UI failure, for every UI test, with nothing for a
 * test to remember.
 *
 * <p>This replaces a convention. The suite this framework was written against
 * captured failure evidence through a manually-set field, at 258 call sites,
 * about fifteen of which were wrong - so those tests attached a screenshot on
 * every passing run and some failures attached nothing. A convention applied 258
 * times is a convention that is applied 243 times.
 *
 * <h2>Why two hooks and not one</h2>
 *
 * <p>The evidence is not all available at the same moment, and this was measured
 * rather than assumed.
 *
 * <ul>
 *   <li>{@code afterTestExecution} runs while the page is still open, so it is
 *       the only place a screenshot can be taken. It is also where the console
 *       and network logs are read, having been collected by listeners attached
 *       in {@code beforeEach}.
 *   <li>The trace is written by Playwright when the browser context closes, and
 *       {@code BrowserContextExtension} does that from its own
 *       {@code TestWatcher}. A {@code TestWatcher} is invoked after every
 *       lifecycle callback, and class-level extensions run before global ones on
 *       the way out, so by the time {@link #testFailed} runs the zip exists.
 * </ul>
 *
 * <p>{@code PageExtension.getOrCreatePage} is in Playwright's {@code impl}
 * package. That is a deliberate, recorded coupling: it is the only way to reach
 * the current page from an extension, and the alternative is asking every test
 * to hand it over - which is the convention this class exists to delete.
 * Playwright is pinned to an exact version, so the coupling cannot drift
 * silently.
 *
 * <p>Registered by {@code ServiceLoader} with extension auto-detection enabled.
 */
public final class FailureEvidence implements BeforeEachCallback, AfterTestExecutionCallback, TestWatcher {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(FailureEvidence.class);

    /** Where {@link ToolshopOptions} tells Playwright to write traces. */
    private static final Path OUTPUT_DIR = Path.of("build", "playwright");

    @Override
    public void beforeEach(ExtensionContext context) {
        Page page = PageExtension.getOrCreatePage(context);
        Journal journal = new Journal();
        context.getStore(NAMESPACE).put(Journal.class, journal);

        page.onConsoleMessage(message ->
                journal.console.add(message.type() + ": " + message.text()));
        page.onPageError(error -> journal.console.add("pageerror: " + error));

        // Only the failures. A full request log is noise; the four calls that
        // answered 4xx are the ones that explain the page.
        page.onResponse(response -> {
            if (response.status() >= 400) {
                journal.failedRequests.add(response.status() + " " + response.request().method()
                        + " " + response.url());
            }
        });
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        if (context.getExecutionException().isEmpty()) {
            return;
        }

        Page page = PageExtension.getOrCreatePage(context);
        attach("Screenshot", "image/png", page.screenshot(), ".png");
        Allure.addAttachment("Page at failure", "text/plain", page.url());

        Journal journal = context.getStore(NAMESPACE).get(Journal.class, Journal.class);
        if (journal != null) {
            if (!journal.failedRequests.isEmpty()) {
                Allure.addAttachment("Failed requests", "text/plain",
                        String.join("\n", journal.failedRequests));
            }
            if (!journal.console.isEmpty()) {
                Allure.addAttachment("Browser console", "text/plain",
                        String.join("\n", journal.console));
            }
        }
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        traceOf(context).ifPresent(trace -> {
            try {
                // The media type Allure renders with its embedded trace viewer,
                // so the trace opens inside the report rather than being a zip
                // to download and load somewhere else.
                attach("Playwright trace", "application/vnd.allure.playwright-trace",
                        Files.readAllBytes(trace), ".zip");
            } catch (IOException e) {
                Allure.addAttachment("Playwright trace", "text/plain",
                        "could not read " + trace + ": " + e.getMessage());
            }
        });
    }

    /**
     * Playwright names the directory after the test, not after the invocation, so
     * every case of a parameterised test writes to the same path and the last
     * one wins. Worth knowing before trusting a trace from a parameterised
     * failure.
     */
    private static Optional<Path> traceOf(ExtensionContext context) {
        String testClass = context.getRequiredTestClass().getName();
        String testMethod = context.getRequiredTestMethod().getName();
        Path trace = OUTPUT_DIR.resolve(testClass + "." + testMethod + "-chromium")
                .resolve("trace.zip");
        return Files.isRegularFile(trace) ? Optional.of(trace) : Optional.empty();
    }

    private static void attach(String name, String type, byte[] content, String extension) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            Allure.addAttachment(name, type, in, extension);
        } catch (IOException e) {
            throw new IllegalStateException("could not attach " + name, e);
        }
    }

    /** Console output and failed requests seen during one test. */
    private static final class Journal {
        private final List<String> console = new CopyOnWriteArrayList<>();
        private final List<String> failedRequests = new CopyOnWriteArrayList<>();
    }
}
