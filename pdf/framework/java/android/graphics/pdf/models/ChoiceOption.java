/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.graphics.pdf.models;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** Represents a single option in a combo box or list box PDF form widget. */
@FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
public final class ChoiceOption implements Parcelable {
    @NonNull
    public static final Creator<ChoiceOption> CREATOR =
            new Creator<ChoiceOption>() {
                @Override
                public ChoiceOption createFromParcel(Parcel in) {
                    return new ChoiceOption(in);
                }

                @Override
                public ChoiceOption[] newArray(int size) {
                    return new ChoiceOption[size];
                }
            };
    private String mLabel;
    private boolean mSelected;

    /**
     * Creates a new choice option with the specified label, and selected state.
     *
     * @param label    Label for choice option.
     * @param selected Determines if the option is selected or not.
     * @throws NullPointerException if {@code label} is null
     */
    public ChoiceOption(@NonNull String label, boolean selected) {
        Preconditions.checkNotNull(label, "Label cannot be null");
        this.mLabel = label;
        this.mSelected = selected;
    }

    private ChoiceOption(@NonNull Parcel in) {
        mLabel = in.readString();
        mSelected = in.readInt() != 0;
    }

    /** @return the label for the choice option in the list */
    @NonNull
    public String getLabel() {
        return mLabel;
    }

    /**
     * Sets the label of this option
     *
     * @throws NullPointerException if {@code label} is null
     */
    public void setLabel(@NonNull String label) {
        Preconditions.checkNotNull(label, "Label cannot be null");
        mLabel = label;
    }

    /** @return {@code true} if the choice option is selected in the list */
    public boolean isSelected() {
        return mSelected;
    }

    /** Sets this option as selected in the list */
    public void setSelected(boolean selected) {
        this.mSelected = selected;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLabel, mSelected);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ChoiceOption other) {
            return mLabel.equals(other.mLabel) && mSelected == other.mSelected;
        }
        return false;
    }

    @Override
    public String toString() {
        return "ChoiceOption{"
                + "\tlabel=" + mLabel + "\n"
                + "\tselected=" + mSelected + "\n"
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mLabel);
        dest.writeInt(mSelected ? 1 : 0);
    }
}
