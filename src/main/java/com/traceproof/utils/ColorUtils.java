package com.traceproof.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 颜色工具
 * <p>
 * 负责把「腾讯云数据万象 imageAve 返回的主色调」以及用户传入的目标颜色，
 * 统一规范成可比较的形式。所有方法都是纯函数，不依赖网络或数据库，便于单测。
 */
public final class ColorUtils {

    /**
     * RGB 欧氏距离的理论最大值：sqrt(255^2 + 255^2 + 255^2) ≈ 441.67
     */
    public static final double MAX_COLOR_DISTANCE = Math.sqrt(255 * 255 * 3);

    private ColorUtils() {
    }

    /**
     * 把各种写法的颜色规范成 {@code #RRGGBB}（大写）。无法解析时返回 null。
     * <p>
     * 支持：#736246 / 0x736246 / 736246 / #123（简写）等；会忽略首尾空白与引号。
     *
     * @param raw 原始颜色串
     * @return 形如 #736246，非法返回 null
     */
    public static String normalizeHex(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        // 去掉可能的引号（imageAve 返回的是纯字符串，这里只是防御）
        s = s.replace("\"", "").replace("'", "");
        if (s.length() == 3) {
            // 简写 #rgb -> #rrggbb
            s = "" + s.charAt(0) + s.charAt(0)
                    + s.charAt(1) + s.charAt(1)
                    + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) {
            return null;
        }
        if (!s.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return "#" + s.toUpperCase();
    }

    /**
     * 把 {@code #RRGGBB} 拆成 [r, g, b]（每个分量 0-255）。入参非法返回 null。
     */
    public static int[] hexToRgb(String hex) {
        String norm = normalizeHex(hex);
        if (norm == null) {
            return null;
        }
        int r = Integer.parseInt(norm.substring(1, 3), 16);
        int g = Integer.parseInt(norm.substring(3, 5), 16);
        int b = Integer.parseInt(norm.substring(5, 7), 16);
        return new int[]{r, g, b};
    }

    /**
     * 计算两个 RGB 的欧氏距离（0 ~ {@link #MAX_COLOR_DISTANCE}）。
     * 任一为 null 时返回最大值（视为「最不相似」）。
     */
    public static double colorDistance(int[] a, int[] b) {
        if (a == null || b == null) {
            return MAX_COLOR_DISTANCE;
        }
        int dr = a[0] - b[0];
        int dg = a[1] - b[1];
        int db = a[2] - b[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
