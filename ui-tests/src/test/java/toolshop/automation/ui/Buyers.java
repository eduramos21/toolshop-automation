package toolshop.automation.ui;

import toolshop.automation.api.ToolshopApi;
import toolshop.automation.core.config.ToolshopConfig;
import toolshop.automation.core.testdata.TestDataRegistry;
import toolshop.automation.data.ToolshopDatabase;

/**
 * Creates a customer that a checkout test can leave in whatever state it likes.
 *
 * <p>A buyer needs more teardown than an account that never orders.
 * {@code DELETE /users/{id}} answers 409 once a customer has an invoice - a
 * foreign key - and the application has no endpoint that removes an invoice, so
 * the invoices have to go first and they have to go through the database.
 *
 * <p>The ordering is the registry's, not this class's: cleanup runs in reverse
 * order of registration, so registering the invoice sweep <em>after</em> the
 * account means it runs <em>before</em> it. That is why this is one call rather
 * than two lines in every test - getting the order backwards produces a 409 at
 * teardown, which is a confusing way to fail a test that passed.
 */
final class Buyers {

    private Buyers() {
    }

    static ToolshopApi.DisposableCustomer create(
            ToolshopApi api, ToolshopConfig config, TestDataRegistry data) {

        ToolshopApi.DisposableCustomer buyer = api.createDisposableCustomer(data);

        ToolshopDatabase database = ToolshopDatabase.from(config);
        data.deleteAfterwards("invoices of " + buyer.credentials().email(),
                () -> database.deleteInvoicesOf(buyer.credentials().email()));

        return buyer;
    }
}
