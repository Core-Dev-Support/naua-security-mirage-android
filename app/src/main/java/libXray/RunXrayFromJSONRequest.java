package libXray;

import go.Seq;
import java.util.Arrays;

public final class RunXrayFromJSONRequest implements Seq.Proxy {
    static {
        LibXray.touch();
    }

    private final int refnum;

    private static native int __New();

    public RunXrayFromJSONRequest() {
        int ref = __New();
        this.refnum = ref;
        Seq.trackGoRef(ref, this);
    }

    RunXrayFromJSONRequest(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(refnum, this);
    }

    @Override
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getConfigJSON();
    public final native String getDatDir();
    public final native void setConfigJSON(String str);
    public final native void setDatDir(String str);

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof RunXrayFromJSONRequest)) {
            return false;
        }
        RunXrayFromJSONRequest other = (RunXrayFromJSONRequest) obj;
        String datDir = getDatDir();
        String otherDatDir = other.getDatDir();
        if (datDir == null ? otherDatDir != null : !datDir.equals(otherDatDir)) {
            return false;
        }
        String configJSON = getConfigJSON();
        String otherConfigJSON = other.getConfigJSON();
        if (configJSON == null ? otherConfigJSON != null : !configJSON.equals(otherConfigJSON)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{getDatDir(), getConfigJSON()});
    }

    @Override
    public String toString() {
        return "RunXrayFromJSONRequest{" + "DatDir:" + getDatDir() + "," + "ConfigJSON:" + getConfigJSON() + "}";
    }
}
