package com.apklama.plus;

import java.io.IOException;

/** Asks the store whether a subscription is paid. {@link Bazaar} in production, a fake in tests. */
interface Purchases {
    /**
     * @return the end of the paid period (epoch ms), or null when the store has no such subscription
     * @throws IOException the store could not answer; nothing should change
     */
    Long validUntil(String packageName, String productId, String purchaseToken) throws IOException;
}
