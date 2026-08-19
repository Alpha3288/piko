/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.download;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.utils.Pref;

public class FilenameFormat {
    public static final String DEFAULT_TEMPLATE = "{username}_{id}";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)(?::([^}]*))?\\}");

    // Reserved on FAT, exFAT and by DocumentsContract display names.
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

    // Short enough that name plus extension survives exFAT.
    private static final int MAX_LENGTH = 127;

    private static final String FALLBACK_NAME = "media";

    public static String build(MediaData media, UserData user, int index, String variantTag, String extension) {
        Map<String, String> values = new HashMap<>();
        values.put("username", read(() -> user.getUsername()));
        values.put("fullname", read(() -> user.getFullName()));
        values.put("userid", read(() -> user.getUserId()));
        values.put("id", read(() -> media.getMediaPkId()));
        values.put("shortcode", read(() -> media.getShortcode()));
        values.put("type", read(() -> media.getPostType().name().toLowerCase(Locale.US)));
        values.put("index", String.valueOf(index));
        values.put("variant", variantTag == null ? "" : variantTag);

        return resolve(Pref.downloadFilenameFormat(), values, media.getPublishedTimeMillis(), extension);
    }

    static String resolve(String template, Map<String, String> values, long publishedTimeMillis, String extension) {
        if (template == null || template.trim().isEmpty()) {
            template = DEFAULT_TEMPLATE;
        }

        StringBuilder out = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int copied = 0;
        boolean variantPlaced = false;
        while (matcher.find()) {
            out.append(template, copied, matcher.start());
            copied = matcher.end();

            String name = matcher.group(1).toLowerCase(Locale.US);
            String pattern = matcher.group(2);
            if (name.equals("date") || name.equals("time")) {
                out.append(formatTime(publishedTimeMillis, name, pattern));
            } else if (values.containsKey(name)) {
                out.append(values.get(name));
                if (name.equals("variant")) {
                    variantPlaced = true;
                }
            } else {
                // An unknown placeholder stays visible so a typo is obvious in the filename.
                out.append(matcher.group());
            }
        }
        out.append(template, copied, template.length());

        String variantTag = values.get("variant");
        // The variants menu names each download by its tag; without a {variant} placeholder in
        // the template, two variants of one media would otherwise share a filename and the
        // collision check would drop the second one, so sanitize reserves room for it below
        // instead of letting truncation cut it away.
        String reservedTag = (!variantPlaced && variantTag != null && !variantTag.isEmpty()) ? variantTag : null;

        return sanitize(out.toString(), reservedTag, extension);
    }

    private static String formatTime(long publishedTimeMillis, String name, String pattern) {
        if (publishedTimeMillis <= 0) {
            return "";
        }
        if (pattern == null || pattern.isEmpty()) {
            // Colons are illegal in filenames, so the clock uses dashes.
            pattern = name.equals("date") ? "yyyy-MM-dd" : "HH-mm-ss";
        }
        try {
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(publishedTimeMillis));
        } catch (Exception e) {
            return "";
        }
    }

    // reservedTag, when non-null, is a variant tag that must survive truncation intact, so it
    // and its joining separator are carved out of the length budget before the base is cut.
    private static String sanitize(String name, String reservedTag, String extension) {
        String cleanedBase = normalize(name);
        String cleanedTag = reservedTag == null ? null : normalize(reservedTag);

        int extensionLength = extension == null ? 0 : extension.length();
        int tagReserve = cleanedTag == null ? 0 : cleanedTag.length() + 1;
        int budget = Math.max(0, MAX_LENGTH - extensionLength - tagReserve);

        String trimmedBase = truncateSafely(cleanedBase, budget)
                .replaceAll("^[._ ]+", "")
                .replaceAll("[._ ]+$", "");

        String result;
        if (cleanedTag == null) {
            result = trimmedBase;
        } else if (trimmedBase.isEmpty()) {
            result = cleanedTag;
        } else {
            result = trimmedBase + "_" + cleanedTag;
        }

        return (result.isEmpty() ? FALLBACK_NAME : result) + extension;
    }

    private static String normalize(String value) {
        return ILLEGAL.matcher(value).replaceAll("_").replaceAll("_{2,}", "_");
    }

    // Trimming and the fallback-name check happen on the caller's side, after this cut, so a
    // separator left dangling by the cut still gets removed.
    private static String truncateSafely(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        int cut = maxLength;
        // A cut that lands right after a high surrogate would orphan it; back off one character
        // instead of splitting the pair.
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut);
    }

    private interface Accessor {
        String get() throws Exception;
    }

    // A placeholder whose accessor fails resolves to nothing rather than losing the whole download.
    private static String read(Accessor accessor) {
        try {
            String value = accessor.get();
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }
}
