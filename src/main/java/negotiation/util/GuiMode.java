package negotiation.util;

import java.awt.GraphicsEnvironment;

/**
 * Central switch for agent Swing windows.
 */
public final class GuiMode {

    private static final String GUI_ENABLED_PROPERTY = "negotiation.gui.enabled";

    private GuiMode() {
    }

    public static void setEnabled(boolean enabled) {
        System.setProperty(GUI_ENABLED_PROPERTY, Boolean.toString(enabled));
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(GUI_ENABLED_PROPERTY, "true"))
                && !GraphicsEnvironment.isHeadless();
    }
}
