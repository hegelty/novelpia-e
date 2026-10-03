package me.crema.novelia.display;

import android.content.SharedPreferences;

/** Immutable software color adjustment. Brightness is an RGB offset, not frontlight control. */
public final class DisplayProfile {
    private static final String KEY_BRIGHTNESS = "display_brightness";
    private static final String KEY_CONTRAST = "display_contrast";
    private static final String KEY_SATURATION = "display_saturation";

    public final int brightness;
    public final int contrast;
    public final int saturation;

    public DisplayProfile(int brightness, int contrast, int saturation) {
        this.brightness = clamp(brightness, -40, 40);
        this.contrast = clamp(contrast, 80, 160);
        this.saturation = clamp(saturation, 0, 100);
    }

    public static DisplayProfile defaults() { return new DisplayProfile(0, 100, 0); }

    public static DisplayProfile load(SharedPreferences preferences) {
        return new DisplayProfile(preferences.getInt(KEY_BRIGHTNESS, 0),
                preferences.getInt(KEY_CONTRAST, 100),
                preferences.getInt(KEY_SATURATION, 0));
    }

    public void save(SharedPreferences preferences) {
        preferences.edit().putInt(KEY_BRIGHTNESS, brightness)
                .putInt(KEY_CONTRAST, contrast).putInt(KEY_SATURATION, saturation).apply();
    }

    public boolean isIdentity() {
        return brightness == 0 && contrast == 100 && saturation == 100;
    }

    /** Android ColorMatrix row-major RGBA transform; alpha is left unchanged. */
    public float[] matrix() {
        float s = saturation / 100f;
        float c = contrast / 100f;
        float r = (1f - s) * 0.213f;
        float g = (1f - s) * 0.715f;
        float b = (1f - s) * 0.072f;
        float offset = 128f * (1f - c) + brightness;
        return new float[] {
                c * (r + s), c * g, c * b, 0, offset,
                c * r, c * (g + s), c * b, 0, offset,
                c * r, c * g, c * (b + s), 0, offset,
                0, 0, 0, 1, 0
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
