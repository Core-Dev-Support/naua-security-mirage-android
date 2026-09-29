package go;

public abstract class Universe {
    static {
        Seq.touch();
        _init();
    }

    private static native void _init();

    public static void touch() {
    }

    static final class proxyerror extends Exception implements Seq.Proxy, error {
        private final int refnum;

        proxyerror(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(refnum, this);
        }

        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        public native String error();

        @Override
        public String getMessage() {
            return error();
        }
    }
}
