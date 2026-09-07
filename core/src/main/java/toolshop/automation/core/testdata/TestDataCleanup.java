package toolshop.automation.core.testdata;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Supplies a {@link TestDataRegistry} per test, and empties it afterwards.
 *
 * <p>{@code afterEach} rather than anything inside a test body, because
 * {@code afterEach} runs when the test fails. That is the entire point: the
 * failing test is the one that leaves data behind.
 *
 * <h2>Why the registry is a test-method parameter and not a constructor one</h2>
 *
 * <p>This was measured, after the first version got it wrong in a way that took
 * real work to diagnose. A {@code ParameterResolver} asked for a
 * <em>constructor</em> parameter receives the <b>class-level</b>
 * {@code ExtensionContext} - {@code [class:CheckoutApiTest]} - while
 * {@code afterEach} receives the <b>method-level</b> one. Store lookups inherit
 * from the parent context, so a registry created during the constructor lands in
 * the class store and every test in the class then shares one.
 *
 * <p>With concurrent test methods that is actively destructive rather than merely
 * untidy: the first test to finish drains the shared registry and deletes
 * entities belonging to tests still running. It surfaced as a login returning
 * 401 with a correct password, because the account had been deleted underneath
 * the test mid-flow - a failure that looks nothing like its cause.
 *
 * <p>So the registry is only ever injected into a test method, where the context
 * is the method's own, and asking for it anywhere else fails immediately with an
 * explanation rather than silently sharing one.
 *
 * <p>Registered by {@code ServiceLoader} with extension auto-detection enabled,
 * so nothing carries an {@code @ExtendWith} for it.
 */
public final class TestDataCleanup implements ParameterResolver, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestDataCleanup.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == TestDataRegistry.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        if (extensionContext.getTestMethod().isEmpty()) {
            throw new ParameterResolutionException("""
                    A TestDataRegistry must be a parameter of the test method, not of the \
                    constructor or of a lifecycle method.

                    A constructor parameter is resolved against the CLASS extension context, so \
                    the registry would be shared by every test in the class. With concurrent test \
                    methods the first test to finish then deletes entities belonging to tests that \
                    are still running.

                    Change the signature to, for example:
                        void aTestThatCreatesData(TestDataRegistry data) { ... }""");
        }
        return extensionContext.getStore(NAMESPACE).computeIfAbsent(
                TestDataRegistry.class, key -> new TestDataRegistry(), TestDataRegistry.class);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TestDataRegistry registry = context.getStore(NAMESPACE)
                .get(TestDataRegistry.class, TestDataRegistry.class);
        if (registry != null) {
            registry.cleanUp();
        }
    }
}
