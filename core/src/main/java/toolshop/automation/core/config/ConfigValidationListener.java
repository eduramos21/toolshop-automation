package toolshop.automation.core.config;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Validates configuration once, before test discovery, so a misconfigured run
 * is a dead run rather than a wall of red tests that look like product bugs.
 *
 * <p>Registered by {@code ServiceLoader} from
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener},
 * so it applies to every module that runs tests and to an IDE gutter run, with
 * nothing to remember and nothing to extend.
 *
 * <p>Measured before this was built, because it is not obvious and the fallback
 * was different: an exception thrown from {@code launcherSessionOpened}
 * <em>does</em> propagate. {@code DefaultLauncherSession} calls session
 * listeners from its constructor, unguarded, so the throw aborts
 * {@code LauncherFactory.openSession} and the run never reaches discovery. On
 * Gradle 9.7.1 that surfaces as {@code BUILD FAILED}, exit 1, zero tests
 * executed, with the message rendered in full including line breaks. This is
 * unlike {@code TestExecutionListener} callbacks, whose exceptions are caught
 * and logged so that one bad listener cannot fail a run.
 *
 * @see ToolshopConfig#get()
 */
public final class ConfigValidationListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        ToolshopConfig.get();
    }
}
