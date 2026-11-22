package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
        public void setThrowable(Throwable throwable) {}
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public class Unhook {
        public void unhook() {}
    }
}
