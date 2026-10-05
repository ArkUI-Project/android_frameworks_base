/*
 * Copyright (C) 2026 The ArkUI Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.view;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * A single, bounded capture of visible screen content. The receiver owns its resources.
 * @hide
 */
public final class ScreenContent implements Parcelable, AutoCloseable {
    public static final int STATUS_OK = 0;
    public static final int STATUS_UNAVAILABLE = 1;
    public static final int STATUS_SECURE = 2;
    public static final int STATUS_CHANGED = 3;

    public int status = STATUS_UNAVAILABLE;
    public int displayId;
    public int rotation;
    public Rect displayBounds = new Rect();
    public long capturedAt;
    public Bitmap screenshot;
    public ArrayList<ScreenContentItem> items = new ArrayList<>();
    public boolean partial;

    public ScreenContent() {}

    private ScreenContent(Parcel in) {
        status = in.readInt();
        displayId = in.readInt();
        rotation = in.readInt();
        displayBounds = in.readTypedObject(Rect.CREATOR);
        capturedAt = in.readLong();
        screenshot = in.readTypedObject(Bitmap.CREATOR);
        items = in.createTypedArrayList(ScreenContentItem.CREATOR);
        if (displayBounds == null) displayBounds = new Rect();
        if (items == null) items = new ArrayList<>();
        partial = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(status);
        out.writeInt(displayId);
        out.writeInt(rotation);
        out.writeTypedObject(displayBounds, flags);
        out.writeLong(capturedAt);
        out.writeTypedObject(screenshot, flags);
        out.writeTypedList(items, flags);
        out.writeBoolean(partial);
    }

    @Override
    public int describeContents() {
        int flags = screenshot == null ? 0 : screenshot.describeContents();
        for (ScreenContentItem item : items) {
            if (item != null) flags |= item.describeContents();
        }
        return flags;
    }

    @Override
    public void close() {
        if (screenshot != null) {
            screenshot.recycle();
            screenshot = null;
        }
        for (ScreenContentItem item : items) {
            if (item != null) item.close();
        }
        items.clear();
    }

    public static final Creator<ScreenContent> CREATOR = new Creator<ScreenContent>() {
        @Override public ScreenContent createFromParcel(Parcel in) { return new ScreenContent(in); }
        @Override public ScreenContent[] newArray(int size) { return new ScreenContent[size]; }
    };
}
