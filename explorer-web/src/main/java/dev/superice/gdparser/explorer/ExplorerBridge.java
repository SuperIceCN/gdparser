package dev.superice.gdparser.explorer;

import org.teavm.jso.JSExport;

public final class ExplorerBridge {
    private ExplorerBridge() {
    }

    @JSExport
    public static String probe() {
        return "gdparser explorer scaffold";
    }
}
