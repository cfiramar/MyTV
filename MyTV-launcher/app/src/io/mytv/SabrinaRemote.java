package io.mytv;

/**
 * Encapsulates hardware identifiers and key mappings for the Chromecast with Google TV ("Sabrina")
 * Bluetooth voice remote ({@code Vendor_18d1_Product_9450.kl}).
 *
 * <p><b>Why:</b> On Chromecast with Google TV, the YouTube shortcut button sends {@code BUTTON_3} (190),
 * which collides with gamepad buttons unless filtered strictly to the physical Sabrina remote's USB/Bluetooth
 * vendor and product IDs.
 *
 * <p><b>What:</b> Identifies the Sabrina device build and verifies whether an {@code InputDevice} matches
 * {@code 0x18d1:0x9450}.
 */
final class SabrinaRemote {
    private SabrinaRemote() {}

    static final int VENDOR_ID = 0x18d1, PRODUCT_ID = 0x9450;
    /** Vendor_18d1_Product_9450.kl maps HID usage 0x000c0077 (YouTube) to KEYCODE_BUTTON_3 (190). */
    static final int KEY_YOUTUBE = 190;

    /** Returns {@code true} when running on Chromecast with Google TV (Sabrina). */
    static boolean supported(String device, String model) {
        return "sabrina".equals(device) && "Chromecast".equals(model);
    }

    /** Returns {@code true} when the input device vendor/product IDs match the physical Sabrina remote. */
    static boolean isRemote(int vendorId, int productId) {
        return vendorId == VENDOR_ID && productId == PRODUCT_ID;
    }
}
