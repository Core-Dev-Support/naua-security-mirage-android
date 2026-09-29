package libXray;

import go.Seq;

public abstract class LibXray {
    static {
        Seq.touch();
        _init();
    }

    private static native void _init();

    public static native String convertShareLinksToXrayJson(String link);

    public static native String convertXrayJsonToShareLinks(String json);

    public static native String countGeoData(String json);

    public static native String getFreePorts(long count);

    public static native boolean getXrayState();

    public static native String newXrayRunFromJSONRequest(String datDir, String configJson);

    public static native String newXrayRunRequest(String datDir, String configPath);

    public static native String ping(String base64);

    public static native String readGeoFiles(String dir);

    public static native void registerDialerController(DialerController controller);

    public static native void registerListenerController(DialerController controller);

    public static native void registerProcessFinder(ProcessFinder finder);

    public static native String runXray(String base64);

    public static native String runXrayFromJSON(String base64);

    public static native void setTunFd(int fd);

    public static native String stopXray();

    public static native String testXray(String base64);

    public static void touch() {
    }

    public static native String xrayVersion();

    static final class proxyDialerController implements Seq.Proxy, DialerController {
        private final int refnum;

        proxyDialerController(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(refnum, this);
        }

        @Override
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override
        public native boolean protectFd(long fd);
    }

    static final class proxyProcessFinder implements Seq.Proxy, ProcessFinder {
        private final int refnum;

        proxyProcessFinder(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(refnum, this);
        }

        @Override
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override
        public native long findProcessByConnection(String str, String str2, long j, String str3, long j2);
    }
}
