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
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.IOException;
import java.util.ArrayList;

/**
 * A visible text run, rendered image, or explicitly exportable local video.
 * @hide
 */
public final class ScreenContentItem implements Parcelable, AutoCloseable {
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 2;
    public static final int TYPE_VIDEO = 3;

    public int type;
    public Rect bounds = new Rect();
    public String text;
    public ArrayList<Rect> characterBounds = new ArrayList<>();
    public Bitmap image;
    public ParcelFileDescriptor descriptor;
    public long offset;
    public long length = -1;
    public String mimeType;
    public String packageName;
    public boolean protectedContent;

    public ScreenContentItem() {}

    public ScreenContentItem(int type, Rect bounds) {
        this.type = type;
        this.bounds = new Rect(bounds);
    }

    private ScreenContentItem(Parcel in) {
        type = in.readInt();
        bounds = in.readTypedObject(Rect.CREATOR);
        text = in.readString();
        characterBounds = in.createTypedArrayList(Rect.CREATOR);
        image = in.readTypedObject(Bitmap.CREATOR);
        descriptor = in.readTypedObject(ParcelFileDescriptor.CREATOR);
        offset = in.readLong();
        length = in.readLong();
        mimeType = in.readString();
        packageName = in.readString();
        protectedContent = in.readBoolean();
        if (bounds == null) bounds = new Rect();
        if (characterBounds == null) characterBounds = new ArrayList<>();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
        out.writeTypedObject(bounds, flags);
        out.writeString(text);
        out.writeTypedList(characterBounds, flags);
        out.writeTypedObject(image, flags);
        // The owner closes descriptors after delivery; avoid closing them while parceling.
        out.writeTypedObject(descriptor, flags & ~PARCELABLE_WRITE_RETURN_VALUE);
        out.writeLong(offset);
        out.writeLong(length);
        out.writeString(mimeType);
        out.writeString(packageName);
        out.writeBoolean(protectedContent);
    }

    @Override
    public int describeContents() {
        return (image == null ? 0 : image.describeContents())
                | (descriptor == null ? 0 : CONTENTS_FILE_DESCRIPTOR);
    }

    @Override
    public void close() {
        if (image != null) {
            image.recycle();
            image = null;
        }
        if (descriptor != null) {
            try { descriptor.close(); } catch (IOException ignored) { }
            descriptor = null;
        }
    }

    public static final Creator<ScreenContentItem> CREATOR = new Creator<ScreenContentItem>() {
        @Override public ScreenContentItem createFromParcel(Parcel in) {
            return new ScreenContentItem(in);
        }
        @Override public ScreenContentItem[] newArray(int size) { return new ScreenContentItem[size]; }
    };
}
