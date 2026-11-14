package com.group5.auctionservice.bidding;

import java.text.Normalizer;

final class BidderNameMask {
    static final String UNKNOWN = "***";

    private BidderNameMask() {
    }

    static String mask(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        int[] letters = Normalizer.normalize(name, Normalizer.Form.NFC).codePoints()
                .filter(Character::isLetter)
                .toArray();
        if (letters.length < 3) {
            return UNKNOWN;
        }
        return new StringBuilder().appendCodePoint(letters[0]).append(UNKNOWN)
                .appendCodePoint(letters[letters.length - 1]).toString();
    }
}
