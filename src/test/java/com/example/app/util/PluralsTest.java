package com.example.app.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluralsTest {

    @ParameterizedTest
    @CsvSource({
            "0, wydarzeń",
            "1, wydarzenie",
            "2, wydarzenia",
            "3, wydarzenia",
            "4, wydarzenia",
            "5, wydarzeń",
            "11, wydarzeń",
            "12, wydarzeń",
            "13, wydarzeń",
            "14, wydarzeń",
            "21, wydarzeń",
            "22, wydarzenia",
            "23, wydarzenia",
            "24, wydarzenia",
            "25, wydarzeń",
            "102, wydarzenia",
            "112, wydarzeń"
    })
    void returnsCorrectFormForCount(int n, String expected) {
        assertThat(Plurals.wydarzenia(n)).isEqualTo(expected);
    }

    @Test
    void rejectsNegativeCount() {
        assertThatThrownBy(() -> Plurals.wydarzenia(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
