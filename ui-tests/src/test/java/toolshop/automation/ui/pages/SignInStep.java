package toolshop.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** Checkout, step two: sign in, or continue as a guest. */
public final class SignInStep {

    private final Page page;

    SignInStep(Page page) {
        this.page = page;
    }

    public SignInStep signIn(String email, String password) {
        page.getByTestId("email").fill(email);
        page.getByTestId("password").fill(password);
        page.getByTestId("login-submit").click();
        return this;
    }

    public Locator loginError() {
        return page.getByTestId("login-error");
    }

    public AddressStep proceed() {
        page.getByTestId("proceed-2").click();
        return new AddressStep(page);
    }
}
