package toolshop.automation.ui;

import com.microsoft.playwright.junit.Options;
import com.microsoft.playwright.junit.OptionsFactory;
import java.nio.file.Path;
import toolshop.automation.core.config.ToolshopConfig;

/**
 * The single Playwright configuration for the whole test JVM.
 *
 * <p>Exactly one of these exists, and that is a constraint rather than a
 * preference. {@code @UsePlaywright} caches {@code Playwright} and
 * {@code Browser} per thread but resolves {@code Options} per <em>class</em>,
 * and never invalidates the cache when the options change. So
 * {@code browserName}, {@code channel}, {@code headless} and
 * {@code launchOptions} cannot vary between classes in one run: a second
 * browser is a second {@code Test} task, not a second factory. See
 * {@code docs/THREADING.md}.
 *
 * <p>Three of the largest problems in a UI suite are answered by five lines
 * here rather than by code anyone writes per test:
 *
 * <ul>
 *   <li><b>{@code setTestIdAttribute("data-test")}</b> makes
 *       {@code page.getByTestId("login-submit")} the default locator strategy
 *       for the whole suite. The application already carries {@code data-test}
 *       on everything worth clicking, so the alternative - XPath - is a choice
 *       nobody has to make.
 *   <li><b>{@code setTrace(RETAIN_ON_FAILURE)}</b> means the evidence for a
 *       failure exists without a test remembering to capture it, and costs
 *       nothing on a passing run.
 *   <li><b>{@code setOutputDir}</b> because the default resolves against
 *       {@code user.dir}, which puts traces outside {@code build/} where
 *       {@code clean} never finds them.
 * </ul>
 *
 * <p>Deliberately not set: {@code contextOptions.setRecordVideoDir}. Video is
 * recorded for every test to be useful on the few that fail, and the trace
 * already carries screenshots, DOM snapshots, console and network for exactly
 * those. If the trace turns out not to be viewable from the report, video is the
 * fallback - and that was measured before deciding. See
 * {@code docs/adr/0005}.
 */
public class ToolshopOptions implements OptionsFactory {

    @Override
    public Options getOptions() {
        ToolshopConfig config = ToolshopConfig.get();

        return new Options()
                .setTestIdAttribute("data-test")
                .setBaseUrl(config.uiBaseUrl().toString())
                .setHeadless(config.headless())
                .setTrace(Options.Trace.RETAIN_ON_FAILURE)
                // Relative, so it resolves against the module directory for both
                // a Gradle test task and an IDE run - and lands inside build/.
                .setOutputDir(Path.of("build", "playwright"));
    }
}
