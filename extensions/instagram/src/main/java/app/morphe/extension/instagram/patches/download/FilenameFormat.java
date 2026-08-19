/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.download;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.utils.Pref;

public class FilenameFormat {
    public static final String DEFAULT_TEMPLATE = "{username}-{id}";

    // Auto-appended parts join with a dash so the boundary stays visible when a value contains
    // underscores.
    private static final String APPENDED_SEPARATOR = "-";

    // Characters that act as separators between template pieces.
    private static final Pattern SEPARATORS = Pattern.compile("[-_. ]");

    // Precompiled once, rather than rebuilding a regex string on every orphan-separator cleanup.
    private static final Pattern LEADING_SEPARATOR_RUN = Pattern.compile("^(?:" + SEPARATORS.pattern() + ")+");
    private static final Pattern TRAILING_SEPARATOR_RUN = Pattern.compile("(?:" + SEPARATORS.pattern() + ")+$");

    // A leading dot hides the file from the gallery, a leading space and a trailing dot or space
    // are invalid on FAT; underscore and dash are excluded so a value's own edge character is
    // never touched, cut or not.
    private static final Pattern LEADING_DOT_OR_SPACE = Pattern.compile("^[. ]+");
    private static final Pattern TRAILING_DOT_OR_SPACE = Pattern.compile("[. ]+$");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)(?::([^}]*))?\\}");

    // Reserved on FAT, exFAT and by DocumentsContract display names.
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

    // Short enough that name plus extension survives exFAT.
    private static final int MAX_LENGTH = 127;

    private static final String FALLBACK_NAME = "media";

    public static String build(MediaData media, MediaData post, UserData user, int index, String variantTag,
                               String extension, boolean requireIndex) {
        Map<String, String> values = new HashMap<>();
        values.put("username", read(() -> user.getUsername()));
        values.put("fullname", read(() -> user.getFullName()));
        values.put("userid", read(() -> user.getUserId()));
        values.put("id", read(() -> media.getMediaPkId()));
        values.put("shortcode", read(() -> media.getShortcode()));
        // The post type describes the post, not the item, so a carousel child cannot report it.
        values.put("type", read(() -> post.getPostType().name().toLowerCase(Locale.US)));
        values.put("index", String.valueOf(index));
        values.put("variant", variantTag == null ? "" : variantTag);

        return resolve(Pref.downloadFilenameFormat(), values, media.getPublishedTimeMillis(), extension, requireIndex);
    }

    static String resolve(String template, Map<String, String> values, long publishedTimeMillis, String extension,
                          boolean requireIndex) {
        if (template == null || template.trim().isEmpty()) {
            template = DEFAULT_TEMPLATE;
        }

        // literals holds one more entry than resolved: the template text before, between and
        // after every placeholder. A null in resolved means that placeholder left nothing behind,
        // which is distinct from a placeholder that legitimately resolves to empty text.
        List<String> literals = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        boolean idPlaced = false;
        boolean indexPlaced = false;
        boolean variantPlaced = false;

        Matcher matcher = PLACEHOLDER.matcher(template);
        int copied = 0;
        while (matcher.find()) {
            literals.add(normalize(template.substring(copied, matcher.start())));
            copied = matcher.end();

            String name = matcher.group(1).toLowerCase(Locale.US);
            String pattern = matcher.group(2);
            String value;
            if (name.equals("date") || name.equals("time")) {
                String formatted = formatTime(publishedTimeMillis, name, pattern);
                value = formatted.isEmpty() ? null : formatted;
            } else if (values.containsKey(name)) {
                String raw = values.get(name);
                value = (raw == null || raw.isEmpty()) ? null : raw;
                if (value != null && name.equals("variant")) {
                    variantPlaced = true;
                }
                if (value != null && name.equals("id")) {
                    idPlaced = true;
                }
                if (value != null && name.equals("index")) {
                    indexPlaced = true;
                }
            } else {
                // An unknown placeholder stays visible so a typo is obvious in the filename.
                value = matcher.group();
            }
            resolved.add(value == null ? null : normalize(value));
        }
        literals.add(normalize(template.substring(copied)));

        // A placeholder that resolved to nothing can leave one of its neighbouring literal
        // separators stranded. Drop exactly one adjacent run, preferring the one that follows, so
        // a template separator disappears but a value's own character never does: once literals
        // and values are joined below there is no way to tell them apart any more.
        for (int i = 0; i < resolved.size(); i++) {
            if (resolved.get(i) != null) {
                continue;
            }
            String next = literals.get(i + 1);
            String stripped = stripLeadingSeparators(next);
            if (!stripped.equals(next)) {
                literals.set(i + 1, stripped);
            } else {
                literals.set(i, stripTrailingSeparators(literals.get(i)));
            }
        }

        StringBuilder base = new StringBuilder(literals.get(0));
        for (int i = 0; i < resolved.size(); i++) {
            String value = resolved.get(i);
            if (value != null) {
                base.append(value);
            }
            base.append(literals.get(i + 1));
        }

        String variantTag = values.get("variant");
        // The variants menu names each download by its tag; without a {variant} placeholder in
        // the template, two variants of one media would otherwise share a filename and the
        // collision check would drop the second one, so sanitize reserves room for it below
        // instead of letting truncation cut it away.
        String reservedTag = (!variantPlaced && variantTag != null && !variantTag.isEmpty()) ? variantTag : null;

        // "Download all" on a multi-child carousel needs some way to tell children apart.
        // {shortcode} does not count: it comes from getPostID(), which is post-level, so every
        // carousel child shares it and it disambiguates nothing.
        String indexValue = (requireIndex && !idPlaced && !indexPlaced) ? values.get("index") : null;

        return sanitize(base.toString(), reservedTag, indexValue, extension);
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

    // reservedTag and indexValue, when non-null, must survive truncation intact, so they and
    // their joining separators are carved out of the length budget before the base is cut.
    private static String sanitize(String base, String reservedTag, String indexValue, String extension) {
        String cleanedTag = reservedTag == null ? null : normalize(reservedTag);
        String cleanedIndex = indexValue == null ? null : normalize(indexValue);

        int extensionLength = extension == null ? 0 : extension.length();
        int tagReserve = cleanedTag == null ? 0 : cleanedTag.length() + APPENDED_SEPARATOR.length();
        int indexReserve = cleanedIndex == null ? 0 : cleanedIndex.length() + APPENDED_SEPARATOR.length();
        int budget = Math.max(0, MAX_LENGTH - extensionLength - tagReserve - indexReserve);

        String cutBase = truncateSafely(base, budget);

        // Leading and trailing dot/space are stripped unconditionally, truncated or not: every
        // production call passes a non-empty extension so this never fires on the final filename
        // today, but a null or empty extension would otherwise let a FAT-invalid trailing dot or
        // space survive. Underscore and dash are never in this class, so a value's own edge
        // character always survives, cut or not.
        String trimmedBase = LEADING_DOT_OR_SPACE.matcher(cutBase).replaceFirst("");
        trimmedBase = TRAILING_DOT_OR_SPACE.matcher(trimmedBase).replaceFirst("");

        List<String> parts = new ArrayList<>();
        if (!trimmedBase.isEmpty()) {
            parts.add(trimmedBase);
        }
        if (cleanedTag != null) {
            parts.add(cleanedTag);
        }
        if (cleanedIndex != null) {
            parts.add(cleanedIndex);
        }

        String result = parts.isEmpty() ? FALLBACK_NAME : String.join(APPENDED_SEPARATOR, parts);
        return result + (extension == null ? "" : extension);
    }

    private static String normalize(String value) {
        return ILLEGAL.matcher(value).replaceAll("_");
    }

    private static String stripLeadingSeparators(String value) {
        return LEADING_SEPARATOR_RUN.matcher(value).replaceFirst("");
    }

    private static String stripTrailingSeparators(String value) {
        return TRAILING_SEPARATOR_RUN.matcher(value).replaceFirst("");
    }

    // The caller strips only a leading or trailing dot or space after this cut; a separator such
    // as an underscore or dash that the cut exposes is left exactly where the cut put it.
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
