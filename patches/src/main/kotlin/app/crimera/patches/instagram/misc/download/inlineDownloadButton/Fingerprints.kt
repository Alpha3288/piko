/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download.inlineDownloadButton

import app.morphe.patcher.Fingerprint
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral

internal const val SAVE_BUTTON_ID = "row_feed_button_save"
internal const val STORY_BUTTONS_CONTAINER_ID = "toolbar_buttons_container"
internal const val STORY_LIKE_BUTTON_ID = "toolbar_like_container"

internal const val REEL_ITEM_CLASS = "Lcom/instagram/model/reels/ReelItem;"
internal const val REEL_VIEWER_CONFIG_CLASS = "Lcom/instagram/model/reels/ReelViewerConfig;"

// Resolved from the view holder fingerprints below, then used to recognise the methods that bind them.
internal var feedUfiHolderClass: String? = null
internal var storyToolbarHolderClass: String? = null

// The feed action bar view holder is the only constructor that resolves the save button by id.
// The same id also appears in the Litho action bar builder, hence the constructor constraints.
internal object FeedUfiViewHolderFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(resourceLiteral(ResourceType.ID, SAVE_BUTTON_ID)),
)

// Only one method in the app takes the feed action bar view holder, and that is the one binding it.
internal object FeedUfiBindFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        feedUfiHolderClass != null && method.parameters.any { it.type == feedUfiHolderClass }
    },
)

// The story toolbar controller resolves the button row by id in its constructor.
internal object StoryToolbarViewHolderFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(resourceLiteral(ResourceType.ID, STORY_BUTTONS_CONTAINER_ID)),
)

// Several methods take the story toolbar controller, but only the full bind also takes the viewer config.
internal object StoryToolbarBindFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        storyToolbarHolderClass != null &&
            method.parameters.any { it.type == storyToolbarHolderClass } &&
            method.parameters.any { it.type == REEL_VIEWER_CONFIG_CLASS }
    },
)
