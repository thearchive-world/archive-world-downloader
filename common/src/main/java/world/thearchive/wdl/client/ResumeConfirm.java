// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import world.thearchive.wdl.core.BrandColors;

public final class ResumeConfirm {
    private ResumeConfirm() {}

    /**
     * Build a resume-confirm screen for {@code folderName} keyed under {@code keyPrefix}; the caller shows it and
     * supplies both actions.
     */
    public static Screen create(String keyPrefix, String folderName, String backupZipName, boolean zipOnResume,
            Runnable onContinue, Runnable onCancel) {
        Component message;
        if (zipOnResume) {
            message = new TranslatableComponent(keyPrefix + ".message", amber(folderName), amber(backupZipName));
        } else {
            message = new TranslatableComponent(keyPrefix + ".message_no_backup", amber(folderName));
        }
        return create(keyPrefix, message, CommonComponents.GUI_PROCEED, CommonComponents.GUI_CANCEL,
                onContinue, onCancel);
    }

    public static Screen create(String keyPrefix, Component message, Component yesLabel, Component noLabel,
            Runnable onYes, Runnable onNo) {
        BooleanConsumer onChoice = confirmed -> {
            if (confirmed) {
                onYes.run();
            } else {
                onNo.run();
            }
        };
        return new ConfirmScreen(onChoice,
                new TranslatableComponent(keyPrefix + ".title")
                        .withStyle(style -> style.withColor(TextColor.fromRgb(BrandColors.AMBER))),
                message,
                yesLabel, noLabel);
    }

    /**
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
                new TranslatableComponent("wdl.screen.downloads.restore_action"), CommonComponents.GUI_CANCEL,
                onRestore, onCancel);
    }

    /**
     * The singleplayer-tainted confirm's restorable variant: the plain tainted body plus the tip line naming the clean
     * source zip a Restore could use instead, keeping the plain confirm's buttons because its yes still resumes.
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
                CommonComponents.GUI_PROCEED, CommonComponents.GUI_CANCEL, onContinue, onCancel);
    }

    private static Component amber(String text) {
        return new TextComponent(text).withStyle(style -> style.withColor(TextColor.fromRgb(BrandColors.AMBER)));
    }
}
