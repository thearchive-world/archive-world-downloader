// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * The shared factory for a resume-confirm screen: a vanilla {@link ConfirmScreen} that warns before a resume writes
 * into an existing folder, its keyed copy selected by {@code keyPrefix}. Every resume confirm has the same shape, an
 * amber folder name, an amber backup zip name shown only when the pre-resume backup will be written, and a
 * title/message pair under one key prefix, so the merge confirm, the singleplayer-tainted confirm, and the
 * map-id-mismatch confirm are one body differing only by prefix. The restore confirm and the blocked-offer screen ride
 * the widened overload instead: they compose their own amber-argument bodies (folder, source zip, snapshot fate) and
 * swap Continue for the Restore verb.
 *
 * <p>{@code keyPrefix} names the copy: {@code <keyPrefix>.title}, {@code <keyPrefix>.message} (folder and backup args),
 * and {@code <keyPrefix>.message_no_backup} (folder arg). The caller resolves the backup and snapshot zip names against
 * the saves directory, counter and all. Continue runs {@code onContinue}, Cancel runs {@code onCancel}; the caller
 * decides where each lands. Loader-agnostic client view code: vanilla widgets only.
 */
public final class ResumeConfirm {
    private ResumeConfirm() {}

    /**
     * Build a resume-confirm screen for {@code folderName} keyed under {@code keyPrefix}; the caller shows it and
     * supplies both actions. The backup reassurance is shown only when {@code zipOnResume} is on, since that is what
     * writes the backup zip.
     */
    public static Screen create(String keyPrefix, String folderName, String backupZipName, boolean zipOnResume,
            Runnable onContinue, Runnable onCancel) {
        Component message;
        if (zipOnResume) {
            message = new TranslatableComponent(keyPrefix + ".message", amber(folderName), amber(backupZipName));
        } else {
            message = new TranslatableComponent(keyPrefix + ".message_no_backup", amber(folderName));
        }
        return create(keyPrefix, message, new TranslatableComponent("gui.proceed"),
                new TranslatableComponent("gui.cancel"),
                onContinue, onCancel);
    }

    /**
     * The widened shape under the same amber title: the caller composes the message body and the verb buttons, so a
     * confirm whose arguments or actions leave the folder/backup pattern still shares the one screen treatment.
     * {@code onYes} runs on the yes button, {@code onNo} on the no button.
     */
    public static Screen create(String keyPrefix, Component message, Component yesLabel, Component noLabel,
            Runnable onYes, Runnable onNo) {
        // This band's ConfirmScreen takes its title, message, and button labels as plain strings and a trailing
        // dialog id, and it draws the title and message in a fixed white, so the brand amber (the gold title and the
        // gold folder and zip names in the body) survives only as the legacy section codes getColoredString emits;
        // getString would strip them and the text would render plain white.
        return new ConfirmScreen((confirmed, dialogId) -> {
            if (confirmed) {
                onYes.run();
            } else {
                onNo.run();
            }
        }, amberComponent(new TranslatableComponent(keyPrefix + ".title")).getColoredString(),
                message.getColoredString(), yesLabel.getString(), noLabel.getString(), 0);
    }

    /**
     * The restore confirm and the blocked-offer screen, differing only by {@code keyPrefix}: the body names the folder,
     * the clean source zip, and the snapshot fate ({@code zipOnResume} picks the kept-snapshot line naming
     * {@code snapshotZipName} over the discarded-permanently line), and the yes verb is Restore rather than Continue.
     * Restore runs {@code onRestore}, Cancel runs {@code onCancel}.
     */
    public static Screen createRestore(String keyPrefix, String folderName, String sourceZipName,
            String snapshotZipName, boolean zipOnResume, Runnable onRestore, Runnable onCancel) {
        Component message;
        if (zipOnResume) {
            message = new TranslatableComponent(keyPrefix + ".message", amber(folderName), amber(sourceZipName),
                    amber(snapshotZipName));
        } else {
            message = new TranslatableComponent(keyPrefix + ".message_no_backup", amber(folderName),
                    amber(sourceZipName));
        }
        return create(keyPrefix, message,
                new TranslatableComponent("wdl.screen.downloads.restore_action"),
                new TranslatableComponent("gui.cancel"),
                onRestore, onCancel);
    }

    /**
     * The singleplayer-tainted confirm's restorable variant: the plain tainted body plus the tip line naming the clean
     * source zip a Restore could use instead, keeping Continue/Cancel because Continue still resumes. The backup
     * reassurance follows {@code zipOnResume} like the plain shape.
     */
    public static Screen createTaintedRestorable(String folderName, String sourceZipName, String backupZipName,
            boolean zipOnResume, Runnable onContinue, Runnable onCancel) {
        Component message;
        if (zipOnResume) {
            message = new TranslatableComponent("wdl.screen.downloads.confirm_tainted.message_restorable",
                    amber(folderName), amber(backupZipName), amber(sourceZipName));
        } else {
            message = new TranslatableComponent("wdl.screen.downloads.confirm_tainted.message_restorable_no_backup",
                    amber(folderName), amber(sourceZipName));
        }
        return create("wdl.screen.downloads.confirm_tainted", message,
                new TranslatableComponent("gui.proceed"), new TranslatableComponent("gui.cancel"), onContinue,
                onCancel);
    }

    private static Component amber(String text) {
        return amberComponent(new TextComponent(text));
    }

    /** Tint {@code component} the nearest vanilla color to the brand amber (gold), mutating its style in place. */
    private static Component amberComponent(Component component) {
        component.getStyle().setColor(ChatFormatting.GOLD);
        return component;
    }
}
