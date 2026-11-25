package javax.microedition.midlet;

/**
 * Clean-room implementation of the MIDP MIDlet base class.
 * Written from scratch for the OwnJ2ME emulator (no third-party code).
 */
public abstract class MIDlet {
    private boolean destroyed;

    protected abstract void startApp();
    protected abstract void pauseApp();
    protected abstract void destroyApp(boolean unconditional);

    /** Signals to the application management software that the MIDlet has been destroyed. */
    public final void notifyDestroyed() {
        destroyed = true;
        dev.ownj2me.Emulator.midletDestroyed(this);
    }

    public final void notifyPaused() {
        dev.ownj2me.Emulator.midletPaused(this);
    }

    public final void resumeRequest() { }

    public final String getAppProperty(String key) {
        return dev.ownj2me.Emulator.getProperty(this, key);
    }

    public final boolean platformRequest(String url) { return false; }

    public final int checkPermission(String permission) { return 1; }

    boolean isDestroyed() { return destroyed; }
}
