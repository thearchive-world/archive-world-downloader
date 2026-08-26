// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.text.ITextComponent;

/**
 * A minimal value-cycling button for this band: it ships no {@code CycleButton}, so the settings screen drives its own.
 * A press advances to the next value, wrapping, and fires the change callback; the face shows the current value only.
 */
final class WdlCycleButton<T> extends GuiButton {
    private final List<T> values;
    private final Function<T, ITextComponent> labelFor;
    private final BiConsumer<WdlCycleButton<T>, T> onChange;
    private int index;

    WdlCycleButton(int x, int y, int width, int height, List<T> values, T initial,
            Function<T, ITextComponent> labelFor, BiConsumer<WdlCycleButton<T>, T> onChange) {
        super(0, x, y, width, height, labelFor.apply(initial).getUnformattedText());
        this.values = values;
        this.labelFor = labelFor;
        this.onChange = onChange;
        this.index = Math.max(0, values.indexOf(initial));
    }

    void setValue(T value) {
        int at = this.values.indexOf(value);
        if (at >= 0) {
            this.index = at;
        }
        this.displayString = this.labelFor.apply(this.values.get(this.index)).getUnformattedText();
    }

    // The pre-1.13 GuiButton has no onPress; a hit advances the value directly, since this control lives inside a
    // settings list row that forwards the click here rather than through the screen's actionPerformed dispatch.
    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!super.mousePressed(mc, mouseX, mouseY)) {
            return false;
        }
        this.index = (this.index + 1) % this.values.size();
        T value = this.values.get(this.index);
        this.displayString = this.labelFor.apply(value).getUnformattedText();
        this.onChange.accept(this, value);
        return true;
    }
}
