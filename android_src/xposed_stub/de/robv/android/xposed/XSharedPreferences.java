package de.robv.android.xposed;

import java.io.File;

public class XSharedPreferences {
    public XSharedPreferences(String packageName) {}
    public XSharedPreferences(String packageName, String prefFileName) {}
    public XSharedPreferences(File prefFile) {}
    public void reload() {}
    public boolean hasFileChanged() { return false; }
    public boolean makeWorldReadable() { return false; }
    public boolean getBoolean(String key, boolean defValue) { return defValue; }
    public int getInt(String key, int defValue) { return defValue; }
    public String getString(String key, String defValue) { return defValue; }
}
