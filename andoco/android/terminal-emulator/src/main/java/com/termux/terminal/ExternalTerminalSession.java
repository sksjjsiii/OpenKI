package com.termux.terminal;

import android.os.Handler;
import android.os.Message;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ExternalTerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();
    TerminalEmulator mEmulator;
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    private final byte[] mUtf8InputBuffer = new byte[5];
    TerminalSessionClient mClient;
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();
    final byte[] mReceiveBuffer = new byte[4096];

    private InputStream mIn;
    private OutputStream mOut;
    private volatile boolean mRunning = true;
    private final Integer mTranscriptRows;

    public ExternalTerminalSession(InputStream in, OutputStream out, Integer transcriptRows, TerminalSessionClient client) {
        this.mIn = in;
        this.mOut = out;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        new Thread("ExtTermIn") {
            @Override public void run() {
                try {
                    byte[] buffer = new byte[4096];
                    while (mRunning) {
                        int read = mIn.read(buffer);
                        if (read == -1) break;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception ignored) {}
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, 0));
            }
        }.start();

        new Thread("ExtTermOut") {
            @Override public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (mRunning) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) break;
                        mOut.write(buffer, 0, bytesToWrite);
                        mOut.flush();
                    }
                } catch (Exception ignored) {}
            }
        }.start();
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }
        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;
        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() { return mEmulator; }
    public void notifyScreenUpdate() { mClient.onTextChanged(null); }
    public synchronized boolean isRunning() { return mRunning; }
    public void finishIfRunning() { cleanupResources(0); }

    public synchronized void cleanupResources(int exitStatus) {
        if (!mRunning) return;
        mRunning = false;
        mProcessToTerminalIOQueue.close();
        mTerminalToProcessIOQueue.close();
        try { if (mIn != null) mIn.close(); } catch (Exception ignored) {}
        try { if (mOut != null) mOut.close(); } catch (Exception ignored) {}
    }

    @Override public void titleChanged(String oldTitle, String newTitle) { mClient.onTitleChanged(null); }
    @Override public void onCopyTextToClipboard(String text) { mClient.onCopyTextToClipboard(null, text); }
    @Override public void onPasteTextFromClipboard() { mClient.onPasteTextFromClipboard(null); }
    @Override public void onBell() { mClient.onBell(null); }
    @Override public void onColorsChanged() { mClient.onColorsChanged(null); }

    public String getTitle() { return mEmulator == null ? null : mEmulator.getTitle(); }

    class MainThreadHandler extends Handler {
        final byte[] mReceiveBuffer = new byte[64 * 1024];
        @Override public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                if (mEmulator != null) mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }
            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);
                String exitDescription = "\r\n[Process completed - press Enter]";
                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                if (mEmulator != null) mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();
                mClient.onSessionFinished(null);
            }
        }
    }
}
