/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download.inlineDownloadButton

import app.morphe.patcher.Fingerprint
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral

internal const val STORY_BUTTONS_CONTAINER_ID = "toolbar_buttons_container"
internal const val STORY_LIKE_BUTTON_ID = "toolbar_like_container"

internal const val REEL_ITEM_CLASS = "Lcom/instagram/model/reels/ReelItem;"
internal const val REEL_VIEWER_CONFIG_CLASS = "Lcom/instagram/model/reels/ReelViewerConfig;"

// Resolved from the view holder fingerprint below, then used to recognise the method that binds it.
internal var storyToolbarHolderClass: String? = null

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
