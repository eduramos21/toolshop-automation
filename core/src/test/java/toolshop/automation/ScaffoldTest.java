package toolshop.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Proves the build, the toolchain and the JUnit 6 platform are wired.
 *
 * <p>This is the only test that will ever assert nothing about the product. It is
 * deleted in P4, when the first real test replaces it.
 */
class ScaffoldTest {

    @Test
    void runsOnJava21OrLater() {
        assertEquals(21, Runtime.version().feature(), "expected the Java 21 toolchain");
    }
}
