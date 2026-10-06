/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import static org.arkui.game.GameStyle.*;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.function.IntConsumer;

/** Quick presets and a freely editable count; zero represents continuous playback. */
final class GameRepeatPicker extends LinearLayout {
    private static final int[] PRESETS = {1,3,10,0};
    private final GameChoiceGroup choices;
    private final EditText count;
    private final IntConsumer listener;
    private int value;
    private boolean updating;

    GameRepeatPicker(Context context,int initial,IntConsumer listener) {
        super(context);
        this.listener = listener;
        value = initial;
        setOrientation(VERTICAL);
        count = input(context,context.getString(R.string.repeat));
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        choices = new GameChoiceGroup(context,new String[]{context.getString(R.string.repeat_choice,1),
                context.getString(R.string.repeat_choice,3),context.getString(R.string.repeat_choice,10),
                context.getString(R.string.repeat_forever_short)},preset(initial),index -> setValue(PRESETS[index],true));
        add(this,choices);
        add(this,count);
        count.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int length,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int length) { }
            @Override public void afterTextChanged(Editable text) {
                if (updating) return;
                Integer number = parse();
                if (number != null) {
                    value = number;
                    count.setError(null);
                    choices.select(preset(value));
                    listener.accept(value);
                }
            }
        });
        setValue(initial,false);
        add(this,text(context,context.getString(R.string.repeat_help),12));
    }

    private static int preset(int value) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == value) return i;
        return -1;
    }
    private void setValue(int next,boolean notify) {
        updating = true;
        value = next;
        count.setEnabled(next != 0);
        count.setAlpha(next == 0 ? .5f : 1);
        count.setHint(getContext().getString(next == 0 ? R.string.repeat_forever : R.string.repeat_custom));
        count.setText(next == 0 ? "" : Integer.toString(next));
        count.setError(null);
        choices.select(preset(next));
        updating = false;
        if (notify) listener.accept(next);
    }
    private Integer parse() {
        try {
            int number = Integer.parseInt(count.getText().toString());
            return number > 0 ? number : null;
        } catch (NumberFormatException e) { return null; }
    }
    Integer repeat() {
        if (value == 0) return 0;
        Integer number = parse();
        if (number == null) { count.setError(getContext().getString(R.string.repeat_required)); count.requestFocus(); }
        return number;
    }
}
