package me.crema.novelia.input;

import android.content.SharedPreferences;

/** Immutable, explicit key/scan mappings. Scan codes are device values, not Android key codes. */
public final class KeyBindings {
    public enum Action { NONE, PREVIOUS, NEXT, MENU, REFRESH }
    private final int[] keys, scans;
    private KeyBindings(int[] keys, int[] scans) { this.keys=keys.clone(); this.scans=scans.clone(); }
    public static KeyBindings defaults() {
        return new KeyBindings(new int[]{0,92,93,82,0},new int[5]);
    }
    public static KeyBindings load(SharedPreferences prefs) {
        if (prefs == null) return defaults();
        try {
            if (!prefs.contains("binding.version")) {
                KeyBindings value=defaults();
                if (prefs.getBoolean("volumeKeys",false)) {
                    value=value.withBinding(Action.PREVIOUS,24,0).withBinding(Action.NEXT,25,0);
                    value.save(prefs);
                }
                return value;
            }
            if (prefs.getInt("binding.version",0)!=1) return defaults();
            KeyBindings value=new KeyBindings(new int[5],new int[5]);
            for (Action action:Action.values()) {
                if(action==Action.NONE) continue;
                String key="binding."+action.name();
                if (!prefs.contains(key)||!prefs.contains(key+".scan")) return defaults();
                int code=prefs.getInt(key,0), scan=prefs.getInt(key+".scan",0);
                if (code!=0||scan!=0) value=value.withBinding(action,code,scan);
            }
            return value;
        } catch (RuntimeException invalid) { return defaults(); }
    }
    public void save(SharedPreferences prefs) {
        SharedPreferences.Editor edit=prefs.edit().putInt("binding.version",1).remove("volumeKeys");
        for(Action action:Action.values()) {
            if(action==Action.NONE) continue;
            edit.putInt("binding."+action.name(),keyCodeOf(action));
            edit.putInt("binding."+action.name()+".scan",scanCodeOf(action));
        }
        edit.apply();
    }
    public Action actionFor(int keyCode,int scanCode) {
        if (protectedKey(keyCode)||keyCode<0||scanCode<0) return Action.NONE;
        // Physical identity wins over a key-code fallback, including unknown vendor keys.
        for(Action a:Action.values()) if(a!=Action.NONE&&scans[a.ordinal()]>0&&scans[a.ordinal()]==scanCode) return a;
        for(Action a:Action.values()) if(a!=Action.NONE&&scans[a.ordinal()]==0&&keys[a.ordinal()]>0&&keys[a.ordinal()]==keyCode) return a;
        return Action.NONE;
    }
    public KeyBindings withBinding(Action action,int keyCode,int scanCode) {
        if(action==null||action==Action.NONE||!isAssignable(keyCode,scanCode))
            throw new IllegalArgumentException("이 키는 지정할 수 없습니다.");
        for(Action a:Action.values()) {
            if(a==Action.NONE||a==action||!isAssigned(a)) continue;
            int key=keys[a.ordinal()],scan=scans[a.ordinal()];
            boolean overlap=scan>0&&scanCode>0 ? scan==scanCode
                    : key>0&&keyCode>0&&key==keyCode;
            if(overlap) throw new IllegalArgumentException("다른 동작에 지정된 키입니다. 먼저 해제하세요.");
        }
        int[] k=keys.clone(),s=scans.clone();k[action.ordinal()]=keyCode;s[action.ordinal()]=scanCode;
        return new KeyBindings(k,s);
    }
    public KeyBindings clearBinding(Action action) {
        if(action==null||action==Action.NONE) throw new IllegalArgumentException("동작을 선택하세요.");
        int[] k=keys.clone(),s=scans.clone();k[action.ordinal()]=0;s[action.ordinal()]=0;
        return new KeyBindings(k,s);
    }
    public int keyCodeOf(Action action) { return keys[action.ordinal()]; }
    public int scanCodeOf(Action action) { return scans[action.ordinal()]; }
    public boolean isAssigned(Action action) { return keyCodeOf(action)!=0||scanCodeOf(action)!=0; }
    public static boolean isAssignable(int keyCode,int scanCode) {
        return keyCode>=0&&keyCode<=65535&&scanCode>=0&&scanCode<=65535
                &&(keyCode!=0||scanCode!=0)&&!protectedKey(keyCode);
    }
    private static boolean protectedKey(int code) {
        switch(code) {
            case 3: case 4: case 26: case 27: case 187: case 219: case 220: case 221: case 223: case 224:
                return true;
            default:return false;
        }
    }
    public static CharSequence describe(int key,int scan) {
        String name;
        switch(key) {
            case 92:name="페이지 위";break;
            case 93:name="페이지 아래";break;
            case 82:name="메뉴 키";break;
            case 24:name="볼륨 위";break;
            case 25:name="볼륨 아래";break;
            case 0:name=scan==0?"미지정":"기기 키";break;
            default:name="키 "+key;
        }
        return name+(scan>0?" · "+scan:"");
    }
}
