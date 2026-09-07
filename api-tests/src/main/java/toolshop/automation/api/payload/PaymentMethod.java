package toolshop.automation.api.payload;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The five payment methods {@code POST /invoices} accepts.
 *
 * <p>An enum rather than a string for the same reason the tags are annotations:
 * {@code "credit_card"} instead of {@code "credit-card"} is a 422 discovered at
 * runtime, and there is no reason to discover it at runtime.
 *
 * <p>Only {@code GIFT_CARD} has its payment details validated by this endpoint -
 * 16 alphanumeric characters and a 4-character code. The others are checked by
 * {@code POST /payment/check} instead, so an invoice will accept nonsense
 * details for them.
 */
public enum PaymentMethod {

    BANK_TRANSFER("bank-transfer"),
    CASH_ON_DELIVERY("cash-on-delivery"),
    CREDIT_CARD("credit-card"),
    BUY_NOW_PAY_LATER("buy-now-pay-later"),
    GIFT_CARD("gift-card");

    private final String wireName;

    PaymentMethod(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
