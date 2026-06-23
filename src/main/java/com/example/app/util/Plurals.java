package com.example.app.util;

public final class Plurals {

    private Plurals() {
    }

    public static String wydarzenia(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be non-negative, got " + n);
        }
        if (n == 1) {
            return "wydarzenie";
        }
        int units = n % 10;
        int tens = (n / 10) % 10;
        if (tens != 1 && (units == 2 || units == 3 || units == 4)) {
            return "wydarzenia";
        }
        return "wydarzeń";
    }
}
