package toolshop.automation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static toolshop.automation.api.ApiAssertions.assertThatStatus;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import toolshop.automation.core.tags.Api;
import toolshop.automation.core.tags.Smoke;
import toolshop.automation.core.tags.Storefront;

@Api
@Storefront
@Epic("Storefront")
@Feature("Product catalogue")
class ProductCatalogueApiTest {

    private final ToolshopApi api;

    ProductCatalogueApiTest(ToolshopApi api) {
        this.api = api;
    }

    @Test
    @Smoke
    @Story("The catalogue is readable without credentials and is paginated")
    @Severity(SeverityLevel.BLOCKER)
    void theCatalogueIsReadableAnonymouslyAndPaginated() {
        // Given no credentials
        // When the catalogue is requested
        Response response = api.anonymous().get("/products");

        // Then it answers with a page of products
        assertThatStatus(response).isEqualTo(200);
        assertThat(response.jsonPath().getString("current_page")).isEqualTo("1");
        assertThat(response.jsonPath().getList("data")).isNotEmpty();
        assertThat(response.jsonPath().getInt("total")).isPositive();
    }

    @Test
    @Story("Every product in a page carries the fields the storefront renders")
    void everyProductCarriesTheFieldsTheStorefrontNeeds() {
        // Given the first page of the catalogue
        Response response = api.anonymous().get("/products");
        assertThatStatus(response).isEqualTo(200);

        // Then no product is missing a price, a name or its stock state
        List<java.util.Map<String, Object>> products = response.jsonPath().getList("data");
        assertThat(products).allSatisfy(product -> {
            assertThat(product.get("id")).as("id").isNotNull();
            assertThat(product.get("name")).as("name of %s", product.get("id")).isNotNull();
            assertThat(product.get("price")).as("price of %s", product.get("id")).isNotNull();
            assertThat(product.get("in_stock")).as("in_stock of %s", product.get("id")).isNotNull();
        });
    }

    @Test
    @Story("A product can be retrieved by id")
    void aProductCanBeRetrievedById() {
        // Given the id of a product that is in stock
        String productId = api.someProductInStock();

        // When it is requested directly
        Response response = api.anonymous().get("/products/" + productId);

        // Then the same product comes back
        assertThatStatus(response).isEqualTo(200);
        assertThat(response.jsonPath().getString("id")).isEqualTo(productId);
        assertThat(response.jsonPath().getBoolean("in_stock")).isTrue();
    }

    @Test
    @Story("An unknown product id is not found")
    void anUnknownProductIdIsNotFound() {
        Response response = api.anonymous().get("/products/no-such-product");

        assertThatStatus(response).isEqualTo(404);
    }
}
