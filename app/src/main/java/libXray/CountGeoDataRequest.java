package libXray;

import go.Seq;
import java.util.Arrays;

public final class CountGeoDataRequest implements Seq.Proxy {
    static {
        LibXray.touch();
    }

    private final int refnum;

    private static native int __New();

    public CountGeoDataRequest() {
        int ref = __New();
        this.refnum = ref;
        Seq.trackGoRef(ref, this);
    }

    CountGeoDataRequest(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(refnum, this);
    }

    @Override
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getDatDir();
    public final native String getGeoType();
    public final native String getName();
    public final native void setDatDir(String str);
    public final native void setGeoType(String str);
    public final native void setName(String str);

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof CountGeoDataRequest)) {
            return false;
        }
        CountGeoDataRequest other = (CountGeoDataRequest) obj;
        String datDir = getDatDir();
        String otherDatDir = other.getDatDir();
        if (datDir == null ? otherDatDir != null : !datDir.equals(otherDatDir)) {
            return false;
        }
        String name = getName();
        String otherName = other.getName();
        if (name == null ? otherName != null : !name.equals(otherName)) {
            return false;
        }
        String geoType = getGeoType();
        String otherGeoType = other.getGeoType();
        if (geoType == null ? otherGeoType != null : !geoType.equals(otherGeoType)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{getDatDir(), getName(), getGeoType()});
    }

    @Override
    public String toString() {
        return "CountGeoDataRequest{" + "DatDir:" + getDatDir() + "," + "Name:" + getName() + "," + "GeoType:" + getGeoType() + "}";
    }
}
