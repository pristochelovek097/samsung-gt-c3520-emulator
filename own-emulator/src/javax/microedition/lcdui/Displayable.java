package javax.microedition.lcdui;

import java.util.Vector;

/** Base class for things that can be shown on a Display. */
public abstract class Displayable {
    private final Vector commands = new Vector();
    private CommandListener listener;
    private String title;

    public void addCommand(Command c) {
        if (c == null) throw new NullPointerException();
        if (!commands.contains(c)) commands.addElement(c);
    }

    public void removeCommand(Command c) { commands.removeElement(c); }

    public void setCommandListener(CommandListener l) { listener = l; }

    public String getTitle() { return title; }
    public void setTitle(String t) { title = t; }

    public int getWidth() {
        return host != null ? host.screenW() : dev.ownj2me.DeviceProfile.defaultWidth;
    }

    public int getHeight() {
        return host != null ? host.screenH() : dev.ownj2me.DeviceProfile.defaultHeight;
    }

    dev.ownj2me.HostFrame host;

    void attach(dev.ownj2me.HostFrame h) {
        host = h;
        sizeChanged(getWidth(), getHeight());
    }

    protected void sizeChanged(int w, int h) { }

    public void fireCommand(Command c) {
        if (listener != null && c != null) listener.commandAction(c, this);
    }

    public Command commandAt(boolean left) {
        if (commands.isEmpty()) return null;
        if (commands.size() == 1) return (Command) commands.elementAt(0);
        return (Command) commands.elementAt(left ? 0 : commands.size() - 1);
    }
}
