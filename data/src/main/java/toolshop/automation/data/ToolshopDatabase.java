package toolshop.automation.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import toolshop.automation.core.config.ToolshopConfig;

/**
 * Read-only access to the application's database, to confirm what a UI or API
 * action actually wrote.
 *
 * <p>Read-only is a rule, not a description. Test data is created through the
 * API so the application's own validation and side effects apply and the row is
 * shaped the way the product shapes it; a row inserted here would skip all of
 * that and then be asserted against as though it were real.
 *
 * <p>{@code PreparedStatement} everywhere, with no string concatenation into
 * SQL. Not because a test parameter is hostile, but because the habit is the
 * thing that matters and there is no reason to keep a second one for tests.
 *
 * <p>A connection per query, and no pool. These are a handful of verification
 * queries at the end of a test; a shared {@code Connection} is not thread safe
 * and a pool would be machinery in service of nothing measurable.
 */
public final class ToolshopDatabase {

    private final ToolshopConfig.Database settings;

    private ToolshopDatabase(ToolshopConfig.Database settings) {
        this.settings = settings;
    }

    /**
     * The database for this run, or a failure explaining that this target has
     * none.
     *
     * <p>Deliberately a failure rather than a skip. A test that quietly does not
     * run is the same green-with-no-signal outcome the empty-selection guard
     * exists to prevent, one level down - so the message names the tag
     * expression that excludes these tests on a target without a database.
     */
    public static ToolshopDatabase from(ToolshopConfig config) {
        return new ToolshopDatabase(config.database().orElseThrow(() -> new IllegalStateException(
                "no database is configured for profile '" + config.profile() + "'.\n"
                + "Database verification only applies to a target whose database is reachable,\n"
                + "which is the local and ci profiles. For a hosted run, exclude these tests:\n"
                + "  ./run test -Ptags='!db'\n"
                + "To configure one, set toolshop.db.url, toolshop.db.username and\n"
                + "TOOLSHOP_DB_PASSWORD - see docs/CONFIGURATION.md.")));
    }

    /**
     * The most recent invoice raised for a customer, by email address.
     *
     * <p>By email rather than by invoice number, because the UI checkout never
     * shows the caller an invoice number - and the test buys as an account whose
     * address is unique to it, so "the most recent invoice for this customer" is
     * unambiguous.
     */
    public Optional<InvoiceRow> latestInvoiceFor(String customerEmail) {
        String sql = """
                SELECT i.id, i.invoice_number, i.billing_street, i.billing_city, i.billing_state,
                       i.billing_country, i.billing_postal_code, i.subtotal, i.total, i.status
                FROM invoices i
                JOIN users u ON u.id = i.user_id
                WHERE u.email = ?
                ORDER BY i.created_at DESC, i.invoice_number DESC
                LIMIT 1
                """;

        return query(sql, statement -> statement.setString(1, customerEmail), results ->
                results.next() ? Optional.of(read(results)) : Optional.empty());
    }

    /** One invoice by its number, which is what the storefront shows the customer. */
    public Optional<InvoiceRow> invoiceNumbered(String invoiceNumber) {
        String sql = """
                SELECT id, invoice_number, billing_street, billing_city, billing_state,
                       billing_country, billing_postal_code, subtotal, total, status
                FROM invoices
                WHERE invoice_number = ?
                """;

        return query(sql, statement -> statement.setString(1, invoiceNumber), results ->
                results.next() ? Optional.of(read(results)) : Optional.empty());
    }

    public List<InvoiceItemRow> itemsOf(String invoiceId) {
        String sql = """
                SELECT id, product_id, quantity, unit_price, discounted_price
                FROM invoice_items
                WHERE invoice_id = ?
                ORDER BY created_at
                """;

        return query(sql, statement -> statement.setString(1, invoiceId), results -> {
            List<InvoiceItemRow> items = new ArrayList<>();
            while (results.next()) {
                items.add(new InvoiceItemRow(
                        results.getString("id"),
                        results.getString("product_id"),
                        results.getInt("quantity"),
                        results.getBigDecimal("unit_price"),
                        results.getBigDecimal("discounted_price")));
            }
            return items;
        });
    }

    /** How many invoices exist for a customer. Used to show a run leaves no residue. */
    public int invoiceCountFor(String customerEmail) {
        String sql = """
                SELECT COUNT(*) AS total
                FROM invoices i
                JOIN users u ON u.id = i.user_id
                WHERE u.email = ?
                """;

        return query(sql, statement -> statement.setString(1, customerEmail), results -> {
            results.next();
            return results.getInt("total");
        });
    }

    /** Whether an account still exists, for confirming that cleanup happened. */
    public boolean customerExists(String customerEmail) {
        return query("SELECT 1 FROM users WHERE email = ? LIMIT 1",
                statement -> statement.setString(1, customerEmail),
                ResultSet::next);
    }

    /**
     * Removes the invoices belonging to a customer, so the account can then be
     * deleted.
     *
     * <p>The only write in this class, and it exists because of a property of the
     * application rather than a preference. {@code DELETE /users/{id}} answers
     * 409 for a customer who has ordered - a foreign key from {@code invoices} -
     * and the API has no endpoint that removes an invoice. So a test that
     * completes a checkout cannot undo it through the application at all.
     *
     * <p>The read-only rule this breaks is about <em>creating</em> data: a row
     * inserted here would skip the validation and side effects that make it real,
     * and would then be asserted against as though the application had written
     * it. Removing what a test created is the opposite - it makes the next run
     * independent of this one, which is the whole point of the registry.
     *
     * <p>Consequence, recorded rather than hidden: a target with no reachable
     * database cannot clean up after a UI checkout, which is why those tests
     * carry {@code @Db}.
     */
    public void deleteInvoicesOf(String customerEmail) {
        // payments -> invoice_items -> invoices, because each references the
        // next. Discovered by the constraint rejecting the delete rather than by
        // reading the schema, which is the honest order to find it in.
        update("""
                DELETE p FROM payments p
                JOIN invoices i ON i.id = p.invoice_id
                JOIN users u ON u.id = i.user_id
                WHERE u.email = ?
                """, customerEmail);

        update("""
                DELETE items FROM invoice_items items
                JOIN invoices i ON i.id = items.invoice_id
                JOIN users u ON u.id = i.user_id
                WHERE u.email = ?
                """, customerEmail);

        update("""
                DELETE i FROM invoices i
                JOIN users u ON u.id = i.user_id
                WHERE u.email = ?
                """, customerEmail);
    }

    private void update(String sql, String parameter) {
        try (Connection connection = DriverManager.getConnection(
                        settings.jdbcUrl(), settings.username(), settings.password());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cleanup failed against " + settings.jdbcUrl()
                    + ": " + e.getMessage() + "\n" + sql, e);
        }
    }

    private static InvoiceRow read(ResultSet results) throws SQLException {
        return new InvoiceRow(
                results.getString("id"),
                results.getString("invoice_number"),
                results.getString("billing_street"),
                results.getString("billing_city"),
                results.getString("billing_state"),
                results.getString("billing_country"),
                results.getString("billing_postal_code"),
                results.getBigDecimal("subtotal"),
                results.getBigDecimal("total"),
                results.getString("status"));
    }

    private <T> T query(String sql, StatementBinder binder, ResultReader<T> reader) {
        try (Connection connection = DriverManager.getConnection(
                        settings.jdbcUrl(), settings.username(), settings.password());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                return reader.read(results);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed against " + settings.jdbcUrl()
                    + ". Is the database running? ./run status\n" + sql, e);
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface ResultReader<T> {
        T read(ResultSet results) throws SQLException;
    }
}
