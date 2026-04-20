package android.hardware.input;

import android.view.InputChannel;

import java.io.Closeable;

/**
 * Compile-only stub for the hidden InputMonitor class.
 * The real implementation is in the Android framework at runtime.
 */
public final class InputMonitor implements Closeable {

    public InputChannel getInputChannel() {
        throw new RuntimeException("Stub!");
    }

    public void pilferPointers() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void close() {
        throw new RuntimeException("Stub!");
    }
}
