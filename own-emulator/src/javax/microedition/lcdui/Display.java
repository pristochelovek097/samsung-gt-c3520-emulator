package javax.microedition.lcdui;

import java.util.Hashtable;
import javax.microedition.midlet.MIDlet;

/** Per-MIDlet display. All displays share the single emulator host window. */
public class Display {
    private static final Hashtable<MIDlet, Display> ALL = new Hashtable<>();

    private final MIDlet owner;
    private Displayable current;

    private Display(MIDlet owner) { this.owner = owner; }

    public static Display getDisplay(MIDlet midlet) {
        if (midlet == null) throw new NullPointerException();
        Display d = ALL.get(midlet);
        if (d == null) { d = new Display(midlet); ALL.put(midlet, d); }
        return d;
    }

    public void setCurrent(Displayable d) {
        current = d;
        dev.ownj2me.HostFrame host = dev.ownj2me.Emulator.getHost();
        host.show(d);
        if (d != null) d.attach(host);
        if (d instanceof Canvas) ((Canvas) d).repaint();
    }

    public Displayable getCurrent() { return current; }

    public boolean flashBacklight(int duration) { return false; }
    public boolean vibrate(int duration) { return false; }
    public int numColors() { return 1 << 24; }
    public boolean isColor() { return true; }

    public void dispose() { ALL.remove(owner); }
}
