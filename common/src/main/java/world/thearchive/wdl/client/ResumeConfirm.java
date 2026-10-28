// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

public final class ResumeConfirm {
    private ResumeConfirm() {}

    /**
     * Build a resume-confirm screen for {@code folderName} keyed under {@code keyPrefix}; the caller shows it and
     * supplies both actions.
     */
    public static GuiScreen create(String keyPrefix, String folderName, String backupZipName, boolean zipOnResume,
            Runnable onContinue, Runnable onCancel) {
        ITextComponent message;
        if (zipOnResume) {
            message = new TextComponentTranslation(keyPrefix + ".message", amber(folderName), amber(backupZipName));
        } else {
            message = new TextComponentTranslation(keyPrefix + ".message_no_backup", amber(folderName));
        }
        return create(keyPrefix, message, new TextComponentTranslation("wdl.screen.downloads.proceed_action"),
                new TextComponentTranslation("gui.cancel"),
                onContinue, onCancel);
    }

    public static GuiScreen create(String keyPrefix, ITextComponent message, ITextComponent yesLabel,
            ITextComponent noLabel,
            Runnable onYes, Runnable onNo) {
        // This band's GuiYesNo draws the title and message in a fixed white, so the brand amber (the gold title and the
        // gold folder and zip names in the body) survives only as the legacy section codes getFormattedText emits;
        // getUnformattedText would strip them and the text would render plain white.
        return new GuiYesNo((confirmed, dialogId) -> {
            if (confirmed) {
                onYes.run();
            } else {
                onNo.run();
            }
        }, amberComponent(new TextComponentTranslation(keyPrefix + ".title")).getFormattedText(),
                message.getFormattedText(), yesLabel.getUnformattedText(), noLabel.getUnformattedText(), 0);
    }

    /**
     * Restore runs {@code onRestore}, Cancel runs {@code onCancel}.
     */
    public static GuiScreen createRestore(String keyPrefix, String folderName, String sourceZipName,
            String snapshotZipName, boolean zipOnResume, Runnable onRestore, Runnable onCancel) {
        ITextComponent message;
        if (zipOnResume) {
            message = new TextComponentTranslation(keyPrefix + ".message", amber(folderName), amber(sourceZipName),
                    amber(snapshotZipName));
        } else {
            message = new TextComponentTranslation(keyPrefix + ".message_no_backup", amber(folderName),
                    amber(sourceZipName));
        }
        return create(keyPrefix, message,
                new TextComponentTranslation("wdl.screen.downloads.restore_action"),
                new TextComponentTranslation("gui.cancel"),
                onRestore, onCancel);
    }

    /**
     * The singleplayer-tainted confirm's restorable variant: the plain tainted body plus the tip line naming the clean
     * source zip a Restore could use instead, keeping the plain confirm's buttons because its yes still resumes.
     */
    public static GuiScreen createTaintedRestorable(String folderName, String sourceZipName, String backupZipName,
            boolean zipOnResume, Runnable onContinue, Runnable onCancel) {
        ITextComponent message;
        if (zipOnResume) {
            message = new TextComponentTranslation("wdl.screen.downloads.confirm_tainted.message_restorable",
                    amber(folderName), amber(backupZipName), amber(sourceZipName));
        } else {
            message = new TextComponentTranslation("wdl.screen.downloads.confirm_tainted.message_restorable_no_backup",
                    amber(folderName), amber(sourceZipName));
        }
        return create("wdl.screen.downloads.confirm_tainted", message,
                new TextComponentTranslation("wdl.screen.downloads.proceed_action"),
                new TextComponentTranslation("gui.cancel"), onContinue,
                onCancel);
    }

    private static ITextComponent amber(String text) {
        return amberComponent(new TextComponentString(text));
    }

    private static ITextComponent amberComponent(ITextComponent component) {
        component.getStyle().setColor(TextFormatting.GOLD);
        return component;
    }
}
