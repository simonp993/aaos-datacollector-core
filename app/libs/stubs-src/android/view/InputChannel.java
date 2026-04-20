package android.view;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Compile-only stub for the hidden InputChannel class.
 * The real implementation is in the Android framework at runtime.
 */
public final class InputChannel implements Parcelable {
    public static final Creator<InputChannel> CREATOR = null;

    @Override
    public int describeContents() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new RuntimeException("Stub!");
    }
}
