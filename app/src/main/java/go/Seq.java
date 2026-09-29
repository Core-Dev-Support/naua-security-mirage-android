package go;

import android.content.Context;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.logging.Logger;

public class Seq {
    private static Logger log = Logger.getLogger("GoSeq");
    public static final Ref nullRef = new Ref(41, null);
    static final RefTracker tracker = new RefTracker();
    private static final GoRefQueue goRefQueue = new GoRefQueue();

    static {
        try {
            System.loadLibrary("gojni");
            init();
            Universe.touch();
        } catch (Throwable t) {
            log.warning("Failed to load gojni: " + t.getMessage());
        }
    }

    private Seq() {
    }

    private static native void init();

    public static void touch() {
    }

    public static void incRefnum(int refnum) {
        tracker.incRefnum(refnum);
    }

    public static int incRef(Object o) {
        return tracker.inc(o);
    }

    public static int incGoObjectRef(GoObject o) {
        return o.incRefnum();
    }

    public static Ref getRef(int refnum) {
        return tracker.get(refnum);
    }

    static void decRef(int refnum) {
        tracker.dec(refnum);
    }

    static native void destroyRef(int refnum);

    public static native void incGoRef(int refnum, GoObject o);

    public static void trackGoRef(int refnum, GoObject o) {
        if (refnum <= 0) {
            goRefQueue.track(refnum, o);
        } else {
            throw new RuntimeException("trackGoRef called with Java refnum " + refnum);
        }
    }

    public static void setContext(Context context) {
        setContext((Object) context);
    }

    static native void setContext(Object context);

    public interface GoObject {
        int incRefnum();
    }

    public interface Proxy extends GoObject {
    }

    public static final class Ref {
        public final int refnum;
        private int refcnt;
        public final Object obj;

        Ref(int refnum, Object obj) {
            if (refnum < 0) {
                throw new RuntimeException("Ref instantiated with a Go refnum " + refnum);
            }
            this.refnum = refnum;
            this.refcnt = 0;
            this.obj = obj;
        }

        synchronized void inc() {
            if (this.refcnt != Integer.MAX_VALUE) {
                this.refcnt++;
            } else {
                throw new RuntimeException("refnum " + this.refnum + " overflow");
            }
        }

        synchronized int dec() {
            return --this.refcnt;
        }

        synchronized int getRefcnt() {
            return this.refcnt;
        }
    }

    static final class RefTracker {
        private int next = 42;
        private final RefMap javaObjs = new RefMap();
        private final IdentityHashMap<Object, Integer> javaRefs = new IdentityHashMap<>();

        synchronized int inc(Object o) {
            if (o == null) {
                return 41;
            }
            if (o instanceof Proxy) {
                return ((Proxy) o).incRefnum();
            }
            Integer refnum = javaRefs.get(o);
            if (refnum == null) {
                if (next == Integer.MAX_VALUE) {
                    throw new RuntimeException("refnum pool exhausted");
                }
                refnum = next++;
                javaRefs.put(o, refnum);
                javaObjs.put(refnum, new Ref(refnum, o));
            }
            Ref ref = javaObjs.get(refnum);
            ref.inc();
            return refnum;
        }

        synchronized void incRefnum(int refnum) {
            Ref ref = javaObjs.get(refnum);
            if (ref != null) {
                ref.inc();
            }
        }

        synchronized Ref get(int refnum) {
            if (refnum < 0) {
                throw new RuntimeException("ref called with Go refnum " + refnum);
            }
            if (refnum == 41) {
                return Seq.nullRef;
            }
            Ref ref = javaObjs.get(refnum);
            if (ref != null) {
                return ref;
            }
            throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
        }

        synchronized void dec(int refnum) {
            if (refnum <= 0) {
                log.severe("dec request for Go object " + refnum);
                return;
            }
            if (refnum == Seq.nullRef.refnum) {
                return;
            }
            Ref ref = javaObjs.get(refnum);
            if (ref != null) {
                if (ref.dec() <= 0) {
                    javaObjs.remove(refnum);
                    javaRefs.remove(ref.obj);
                }
                return;
            }
            throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
        }
    }

    static final class RefMap {
        private final java.util.HashMap<Integer, Ref> map = new java.util.HashMap<>();

        Ref get(int key) {
            return map.get(key);
        }

        void put(int key, Ref value) {
            map.put(key, value);
        }

        void remove(int key) {
            map.remove(key);
        }
    }

    static class GoRefQueue extends ReferenceQueue<GoObject> {
        private final Collection<GoRef> refs = Collections.synchronizedCollection(new HashSet<GoRef>());

        GoRefQueue() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            GoRef ref = (GoRef) GoRefQueue.this.remove();
                            refs.remove(ref);
                            Seq.destroyRef(ref.refnum);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.setName("GoRefQueue Finalizer Thread");
            thread.start();
        }

        void track(int refnum, GoObject obj) {
            refs.add(new GoRef(refnum, obj, this));
        }
    }

    static final class GoRef extends PhantomReference<GoObject> {
        final int refnum;

        GoRef(int refnum, GoObject obj, GoRefQueue queue) {
            super(obj, queue);
            this.refnum = refnum;
        }
    }
}
