package com.group5.ratingservice.rating.util;

import java.text.Normalizer;

public final class RaterNameMask {
    private RaterNameMask() {
    }

    public static String mask(String name) {
        if (name == null) {
            return "***";
        }
        int[] letters = Normalizer.normalize(name, Normalizer.Form.NFC).codePoints()
                .filter(Character::isLetter)
                .toArray();
        if (letters.length < 3) {
            return "***";
        }
        return new StringBuilder().appendCodePoint(letters[0]).append("***")
                .appendCodePoint(letters[letters.length - 1]).toString();
    }
}
