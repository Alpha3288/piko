/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import app.morphe.extension.crimera.downloader.MediaType;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

import com.instagram.common.session.UserSession;

// Adds a download button to the story toolbar, left of the like button.
// The feed post row is not covered: it is rendered by IG's Litho UFI, which mounts
// look-alike views but binds through a path this view-level hook cannot reach.
public class InlineDownloadButton {

    // Read once: the entry point runs on every toolbar bind, which is a hot path.
    private static final boolean ENABLED;

    static {
        ENABLED = Pref.inlineDownloadButton() && Pref.enableDownload();
    }

    /**
     * Holds the media the button currently points at, and doubles as the marker that
     * identifies our own view when a recycled toolbar is bound again.
     */
    private static final class Target {
        Object media;
        UserSession session;
    }

    public static void addToStoryToolbar(View buttonsContainer, View likeButton, Object media, UserSession session) {
        try {
            if (!ENABLED || media == null || likeButton == null || !(buttonsContainer instanceof ViewGroup)) {
                return;
            }
            bind((ViewGroup) buttonsContainer, likeButton, media, session);
        } catch (Exception e) {
            Logger.printException(() -> "Failed addToStoryToolbar", e);
        }
    }

    private static void bind(ViewGroup parent, View anchor, Object media, UserSession session) {
        Target target = existingTarget(parent);
        if (target == null) {
            target = addButton(parent, anchor);
        }
        target.media = media;
        target.session = session;
    }

    private static Target existingTarget(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Object tag = parent.getChildAt(i).getTag();
            if (tag instanceof Target) {
                return (Target) tag;
            }
        }
        return null;
    }

    private static Target addButton(ViewGroup parent, View anchor) {
        final Target target = new Target();
        ImageView button = new ImageView(parent.getContext());

        UI.setThemedIcon(button, UI.DRAWABLE_DOWNLOAD_ICON);
        // The story toolbar draws on top of the media, where the native icons ignore the theme.
        button.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(str("piko_category_download_media"));
        button.setTag(target);

        // Matching the neighbour keeps the button aligned with the row it is inserted into.
        ViewGroup.LayoutParams anchorParams = anchor.getLayoutParams();
        if (anchorParams != null) {
            button.setLayoutParams(new ViewGroup.MarginLayoutParams(anchorParams));
        }

        button.setOnClickListener(v -> download(v, target));

        parent.addView(button, parent.indexOfChild(anchor));
        return target;
    }

    private static void download(View view, Target target) {
        try {
            Object media = target.media;
            if (media == null) {
                return;
            }
            // Stories are single-media, so position 0 is the whole item.
            DownloadUtils.downloadMedia(view.getContext(), new MediaData(media, target.session), 0, MediaType.ANY);
        } catch (Exception e) {
            Logger.printException(() -> "Failed inline download", e);
        }
    }
}
