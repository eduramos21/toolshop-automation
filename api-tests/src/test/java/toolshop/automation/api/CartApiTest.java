package toolshop.automation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static toolshop.automation.api.ApiAssertions.assertThatStatus;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toolshop.automation.api.payload.CartItem;
import toolshop.automation.core.tags.Api;
import toolshop.automation.core.tags.Checkout;
import toolshop.automation.core.tags.Smoke;

@Api
@Checkout
@Epic("Checkout")
@Feature("Cart")
class CartApiTest {

    private final ToolshopApi api;

    CartApiTest(ToolshopApi api) {
        this.api = api;
    }

    @Test
    @Smoke
    @Story("A cart can be created without credentials")
    @Severity(SeverityLevel.BLOCKER)
    void aCartCanBeCreatedAnonymously() {
        // Given no credentials - the storefront builds a cart before login
        // When a cart is created
        Response response = api.anonymous().post("/carts");

        // Then it exists and has an id
        assertThatStatus(response).isEqualTo(201);
        assertThat(response.jsonPath().getString("id")).isNotBlank();
    }

    @Test
    @Smoke
    @Story("An item added to a cart comes back with its quantity")
    @Severity(SeverityLevel.CRITICAL)
    void anItemAddedToACartIsReturnedWithItsQuantity() {
        // Given an empty cart and a product that is in stock
        String productId = api.anyProductInStock().id();
        String cartId = api.anonymous().post("/carts").jsonPath().getString("id");

        // When two of it are added
        Response added = api.anonymous().body(new CartItem(productId, 2)).post("/carts/" + cartId);

        // Then the cart holds exactly that
        assertThatStatus(added).isEqualTo(200);

        Response cart = api.anonymous().get("/carts/" + cartId);
        assertThatStatus(cart).isEqualTo(200);
        assertThat(cart.jsonPath().getList("cart_items")).hasSize(1);
        assertThat(cart.jsonPath().getString("cart_items[0].product_id")).isEqualTo(productId);
        assertThat(cart.jsonPath().getInt("cart_items[0].quantity")).isEqualTo(2);
    }

    @Test
    @Story("A product that does not exist cannot be added")
    void aProductThatDoesNotExistCannotBeAdded() {
        // Given a cart and an id no product has
        String cartId = api.anonymous().post("/carts").jsonPath().getString("id");

        // When it is added
        Response response = api.anonymous()
                .body(new CartItem("01JFG8Q5XKZJY4BEYQ87PC2Q1Y", 1))
                .post("/carts/" + cartId);

        // Then it is refused as invalid input rather than accepted
        assertThatStatus(response).isEqualTo(422);
        assertThat(response.asString()).contains("product_id");
    }

    @Test
    @Story("A quantity below one is rejected")
    void aQuantityBelowOneIsRejected() {
        String productId = api.anyProductInStock().id();
        String cartId = api.anonymous().post("/carts").jsonPath().getString("id");

        Response response = api.anonymous()
                .body(new CartItem(productId, 0))
                .post("/carts/" + cartId);

        assertThatStatus(response).isEqualTo(422);
        assertThat(response.asString()).contains("quantity");
    }

    @Test
    @Story("A missing product id is reported rather than ignored")
    void aMissingProductIdIsReported() {
        String cartId = api.anonymous().post("/carts").jsonPath().getString("id");

        Response response = api.anonymous().body(Map.of("quantity", 1)).post("/carts/" + cartId);

        assertThatStatus(response).isEqualTo(422);
        assertThat(response.asString()).contains("product_id");
    }

    @Test
    @Story("An unknown cart is not found")
    void anUnknownCartIsNotFound() {
        Response response = api.anonymous().get("/carts/no-such-cart");

        assertThatStatus(response).isEqualTo(404);
    }
}
