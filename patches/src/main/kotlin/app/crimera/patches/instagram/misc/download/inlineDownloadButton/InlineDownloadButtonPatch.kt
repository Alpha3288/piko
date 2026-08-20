/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download.inlineDownloadButton

import app.crimera.patches.instagram.entity.decoder.CURRENT_MEDIA_FIELD
import app.crimera.patches.instagram.entity.decoder.MEDIA_ADD_INFO_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "$DOWNLOAD_DESCRIPTOR/InlineDownloadButton;"

// The injected code runs before the method body, so it borrows v0 to v3.
private const val SCRATCH_REGISTERS = 4

/**
 * Resolves the field a view holder stores a view in, by walking from the resource id it looks up.
 * Matching on the id keeps this independent of the obfuscated field names.
 */
private fun MutableMethod.fieldAssignedFromResourceId(resourceId: Long): FieldReference {
    val constIndex =
        instructions
            .first { it.opcode == Opcode.CONST && (it as WideLiteralInstruction).wideLiteral == resourceId }
            .location
            .index

    val assignIndex = indexOfFirstInstruction(constIndex, Opcode.IPUT_OBJECT)
    if (assignIndex < 0) {
        throw PatchException("No field assignment follows resource id $resourceId in $name")
    }
    return getInstruction(assignIndex).getReference<FieldReference>()!!
}

/** Register of parameter [index], where index 0 is the first declared parameter. */
private fun Method.parameterRegister(index: Int): String {
    val thisOffset = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    return "p${index + thisOffset}"
}

/** Fails the patch rather than emitting code that would overwrite the method's own parameters. */
private fun MutableMethod.requireScratchRegisters() {
    val implementation = implementation ?: throw PatchException("$name has no implementation")
    val parameterRegisters =
        parameters.sumOf { if (it.type == "J" || it.type == "D") 2 else 1 } +
            if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1

    if (implementation.registerCount - parameterRegisters < SCRATCH_REGISTERS) {
        throw PatchException("$name has fewer than $SCRATCH_REGISTERS free registers")
    }
}

@Suppress("unused")
val inlineDownloadButtonPatch =
    bytecodePatch(
        description = "Adds a download button next to the native action bar buttons on posts and stories",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, decoderEntity, resourceMappingPatch)

        execute {

            // Posts: insert to the left of the save button, which is the last child of the action bar row.
            FeedUfiViewHolderFingerprint.apply {
                feedUfiHolderClass = classDef.type

                val saveButtonField =
                    method.fieldAssignedFromResourceId(getResourceId(ResourceType.ID, SAVE_BUTTON_ID))

                FeedUfiBindFingerprint.apply {
                    val userSessionField = classDef.fields.firstOrNull { it.type == USER_SESSION_CLASS }

                    method.apply {
                        requireScratchRegisters()

                        val holderRegister = parameterRegister(parameters.indexOfFirst { it.type == feedUfiHolderClass })

                        // The class carrying the media also carries nothing else of that type, so match by field.
                        val mediaParameterIndex =
                            parameters.indexOfFirst { parameter ->
                                classDefByOrNull(parameter.type)?.fields?.any { it.type == MEDIA_CLASS_NAME } == true
                            }
                        if (mediaParameterIndex < 0) {
                            throw PatchException("No parameter of $name carries a $MEDIA_CLASS_NAME field")
                        }
                        val mediaField =
                            classDefBy(parameters[mediaParameterIndex].type).fields.first { it.type == MEDIA_CLASS_NAME }
                        val mediaRegister = parameterRegister(mediaParameterIndex)

                        // The same state object the overflow menu reads the visible carousel index from.
                        val stateParameterIndex = parameters.indexOfFirst { it.type == MEDIA_ADD_INFO_CLASS_NAME }
                        if (stateParameterIndex < 0) {
                            throw PatchException("No parameter of $name carries the carousel index")
                        }
                        val stateRegister = parameterRegister(stateParameterIndex)

                        val loadUserSession =
                            if (userSessionField != null) {
                                """
                                move-object/from16 v3, p0
                                iget-object v3, v3, $userSessionField
                                """
                            } else {
                                "const/4 v3, 0x0"
                            }

                        addInstructions(
                            0,
                            """
                            move-object/from16 v0, $holderRegister
                            iget-object v0, v0, $saveButtonField
                            move-object/from16 v1, $mediaRegister
                            iget-object v1, v1, $mediaField
                            move-object/from16 v2, $stateRegister
                            iget v2, v2, $CURRENT_MEDIA_FIELD
                            $loadUserSession
                            invoke-static {v0, v1, v2, v3}, $EXTENSION_CLASS_DESCRIPTOR->addToPostActionBar(Landroid/view/View;Ljava/lang/Object;I$USER_SESSION_CLASS)V
                            """.trimIndent(),
                        )
                    }
                }
            }

            // Stories: insert to the left of the like button inside the toolbar button row.
            StoryToolbarViewHolderFingerprint.apply {
                storyToolbarHolderClass = classDef.type

                val buttonsContainerField =
                    method.fieldAssignedFromResourceId(getResourceId(ResourceType.ID, STORY_BUTTONS_CONTAINER_ID))
                val likeButtonField =
                    method.fieldAssignedFromResourceId(getResourceId(ResourceType.ID, STORY_LIKE_BUTTON_ID))

                val reelItemMediaField =
                    classDefBy(REEL_ITEM_CLASS).fields.last { it.type == MEDIA_CLASS_NAME }

                StoryToolbarBindFingerprint.method.apply {
                    requireScratchRegisters()

                    val holderRegister = parameterRegister(parameters.indexOfFirst { it.type == storyToolbarHolderClass })
                    val reelItemRegister = parameterRegister(parameters.indexOfFirst { it.type == REEL_ITEM_CLASS })
                    val userSessionRegister = parameterRegister(parameters.indexOfFirst { it.type == USER_SESSION_CLASS })

                    addInstructions(
                        0,
                        """
                        move-object/from16 v3, $holderRegister
                        iget-object v0, v3, $buttonsContainerField
                        iget-object v1, v3, $likeButtonField
                        move-object/from16 v2, $reelItemRegister
                        iget-object v2, v2, $reelItemMediaField
                        move-object/from16 v3, $userSessionRegister
                        invoke-static {v0, v1, v2, v3}, $EXTENSION_CLASS_DESCRIPTOR->addToStoryToolbar(Landroid/view/View;Landroid/view/View;Ljava/lang/Object;$USER_SESSION_CLASS)V
                        """.trimIndent(),
                    )
                }
            }

            enableSettings("inlineDownloadButton")
        }
    }
