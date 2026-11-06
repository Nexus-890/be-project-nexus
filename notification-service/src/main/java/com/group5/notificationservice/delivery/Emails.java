package com.group5.notificationservice.delivery;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Emails {

    private static final Pattern ADDRESS = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+)");

    private Emails() {
    }

    // KHÔNG BAO GIỜ GHI EMAIL ĐẦY ĐỦ VÀO LOG HAY DB: buyer@gmail.com -> b***@gmail.com
    static String mask(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = ADDRESS.matcher(text);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "***@" + match.group(2)));
    }
}
