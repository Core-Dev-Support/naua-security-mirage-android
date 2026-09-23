package libXray;

import go.Seq;
import java.util.Arrays;

public final class RunXrayRequest implements Seq.Proxy {
    static {
        LibXray.touch();
    }

    private final int refnum;

    private static native int __New();

    public RunXrayRequest() {
        int ref = __New();
        this.refnum = ref;
        Seq.trackGoRef(ref, this);
    }

    RunXrayRequest(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(refnum, this);
    }

    @Override
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getConfigPath();
    public final native String getDatDir();
    public final native void setConfigPath(String str);
    public final native void setDatDir(String str);

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof RunXrayRequest)) {
            return false;
        }
        RunXrayRequest other = (RunXrayRequest) obj;
        String datDir = getDatDir();
        String otherDatDir = other.getDatDir();
        if (datDir == null ? otherDatDir != null : !datDir.equals(otherDatDir)) {
            return false;
        }
        String configPath = getConfigPath();
        String otherConfigPath = other.getConfigPath();
        if (configPath == null ? otherConfigPath != null : !configPath.equals(otherConfigPath)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{getDatDir(), getConfigPath()});
    }

    @Override
    public String toString() {
        return "RunXrayRequest{" + "DatDir:" + getDatDir() + "," + "ConfigPath:" + getConfigPath() + "}";
    }
}
