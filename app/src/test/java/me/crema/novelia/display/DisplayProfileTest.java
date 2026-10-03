package me.crema.novelia.display;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayProfileTest {
    private static float[] rgb(DisplayProfile profile, int r, int g, int b, int a) {
        float[] m = profile.matrix();
        float[] result = new float[4];
        for (int row = 0; row < 4; row++) {
            int i = row * 5;
            result[row] = m[i] * r + m[i + 1] * g + m[i + 2] * b
                    + m[i + 3] * a + m[i + 4];
        }
        return result;
    }

    @Test public void identityDoesNotAlterAnyChannel() {
        DisplayProfile p = new DisplayProfile(0, 100, 100);
        assertTrue(p.isIdentity());
        assertArrayEquals(new float[] { 34, 123, 211, 67 }, rgb(p, 34, 123, 211, 67), 0.001f);
    }

    @Test public void defaultsMakeColorGrayButPreserveNeutralTones() {
        DisplayProfile p = DisplayProfile.defaults();
        assertFalse(p.isIdentity());
        float[] red = rgb(p, 255, 0, 0, 99);
        assertEquals(red[0], red[1], 0.001f);
        assertEquals(red[1], red[2], 0.001f);
        assertEquals(99, red[3], 0.001f);
        assertArrayEquals(new float[] { 180, 180, 180, 99 }, rgb(p, 180, 180, 180, 99), 0.001f);
    }

    @Test public void clampsAllControls() {
        DisplayProfile low = new DisplayProfile(-100, 20, -20);
        assertEquals(-40, low.brightness);
        assertEquals(80, low.contrast);
        assertEquals(0, low.saturation);
        DisplayProfile high = new DisplayProfile(100, 300, 200);
        assertEquals(40, high.brightness);
        assertEquals(160, high.contrast);
        assertEquals(100, high.saturation);
    }

    @Test public void contrastAroundMidpointThenBrightnessOffset() {
        DisplayProfile p = new DisplayProfile(10, 150, 100);
        assertArrayEquals(new float[] { 138, 138, 138, 42 }, rgb(p, 128, 128, 128, 42), 0.001f);
        assertArrayEquals(new float[] { 96, 96, 96, 42 }, rgb(p, 100, 100, 100, 42), 0.001f);
    }
}
