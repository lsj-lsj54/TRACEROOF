package com.traceproof.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorUtilsTest {

    @Test
    @DisplayName("normalizeHex 接受 # / 0x / 纯十六进制 / 简写 多种写法")
    void normalizeHexAcceptsVariants() {
        assertEquals("#736246", ColorUtils.normalizeHex("#736246"));
        assertEquals("#736246", ColorUtils.normalizeHex("0x736246"));
        assertEquals("#736246", ColorUtils.normalizeHex("736246"));
        assertEquals("#112233", ColorUtils.normalizeHex("#123"));
        assertEquals("#AABBCC", ColorUtils.normalizeHex("0xAABBCC"));
    }

    @Test
    @DisplayName("normalizeHex 对非法输入返回 null")
    void normalizeHexRejectsInvalid() {
        assertNull(ColorUtils.normalizeHex(null));
        assertNull(ColorUtils.normalizeHex(""));
        assertNull(ColorUtils.normalizeHex("xyz"));
        assertNull(ColorUtils.normalizeHex("#12"));
        assertNull(ColorUtils.normalizeHex("#12345"));
    }

    @Test
    @DisplayName("hexToRgb 拆分正确，非法返回 null")
    void hexToRgbSplits() {
        assertArrayEquals(new int[]{0x73, 0x62, 0x46}, ColorUtils.hexToRgb("#736246"));
        assertNull(ColorUtils.hexToRgb("notacolor"));
    }

    @Test
    @DisplayName("colorDistance 同色为0、黑白最大、相近色小于50")
    void colorDistanceBounds() {
        assertEquals(0.0, ColorUtils.colorDistance(new int[]{0, 0, 0}, new int[]{0, 0, 0}), 1e-9);
        assertEquals(ColorUtils.MAX_COLOR_DISTANCE,
                ColorUtils.colorDistance(new int[]{0, 0, 0}, new int[]{255, 255, 255}), 1e-6);
        assertTrue(ColorUtils.colorDistance(new int[]{100, 100, 100}, new int[]{120, 120, 120}) < 50);
        assertFalse(ColorUtils.colorDistance(new int[]{0, 0, 0}, new int[]{0, 0, 0}) > 0);
    }
}
