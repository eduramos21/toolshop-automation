package toolshop.automation.core.testdata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Everything one test created, and how to remove it.
 *
 * <p>This exists because of the single worst pattern in the suite this framework
 * was written against: cleanup written at the end of a test body. That runs only
 * when the test passes - which is exactly when there is nothing interesting left
 * behind. A test that fails halfway is the one that created the row and then
 * abandoned it, and it is the only case where cleanup matters.
 *
 * <p>So cleanup is registered at creation time and run by
 * {@link TestDataCleanup} in {@code afterEach}, which runs on failure too. A
 * test cannot forget, because the only way to create a disposable entity is to
 * hand over how to delete it.
 *
 * <p>Not thread safe, and does not need to be: one instance per test, held in
 * that test's own extension store.
 */
public final class TestDataRegistry {

    /** Reverse order of creation, so a dependent entity goes before what it depends on. */
    private final Deque<Entry> pending = new ArrayDeque<>();

    public void deleteAfterwards(String description, Runnable delete) {
        pending.push(new Entry(description, delete));
    }

    /** Whether anything is still registered. Used by the tests of this class. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Removes everything, most recent first.
     *
     * <p>Every entry is attempted even when an earlier one fails, because
     * stopping at the first failure would leave the rest behind - and then the
     * next run inherits it. Failures are collected and reported together.
     */
    void cleanUp() {
        List<String> failures = new ArrayList<>();

        while (!pending.isEmpty()) {
            Entry entry = pending.pop();
            try {
                entry.delete().run();
            } catch (RuntimeException e) {
                failures.add(entry.description() + ": " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "test data was left behind, so the next run inherits it:\n  "
                    + String.join("\n  ", failures));
        }
    }

    private record Entry(String description, Runnable delete) {
    }
}
