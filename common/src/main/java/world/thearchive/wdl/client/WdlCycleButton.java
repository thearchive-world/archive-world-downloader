// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;

/**
 * A minimal value-cycling button for this band: 1.16.5 ships no {@code CycleButton}, so the settings screen drives its
 * own. A press advances to the next value, wrapping, and fires the change callback; the face shows the current value
 * only.
 */
final class WdlCycleButton<T> extends AbstractButton {
    private final List<T> values;
    private final Function<T, Component> labelFor;
    private final BiConsumer<WdlCycleButton<T>, T> onChange;
    private int index;

    WdlCycleButton(int x, int y, int width, int height, List<T> values, T initial,
            Function<T, Component> labelFor, BiConsumer<WdlCycleButton<T>, T> onChange) {
        super(x, y, width, height, labelFor.apply(initial));
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
        setMessage(this.labelFor.apply(this.values.get(this.index)));
    }

    @Override
    public void onPress() {
        this.index = (this.index + 1) % this.values.size();
        T value = this.values.get(this.index);
        setMessage(this.labelFor.apply(value));
        this.onChange.accept(this, value);
    }
}
