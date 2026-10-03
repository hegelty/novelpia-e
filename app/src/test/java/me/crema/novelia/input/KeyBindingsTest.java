package me.crema.novelia.input;
import org.junit.Test;
import static org.junit.Assert.*;
import me.crema.novelia.input.KeyBindings.Action;

public class KeyBindingsTest {
    @Test public void defaultsAndVolumeUntouched() {
        KeyBindings k=KeyBindings.defaults();
        assertEquals(Action.PREVIOUS,k.actionFor(92,104));assertEquals(Action.NEXT,k.actionFor(93,109));
        assertEquals(Action.MENU,k.actionFor(82,139));assertEquals(Action.NONE,k.actionFor(24,115));
    }
    @Test public void unknownVendorScanAndDistinctPhysicalKeys() {
        KeyBindings k=KeyBindings.defaults().withBinding(Action.PREVIOUS,0,501).withBinding(Action.NEXT,0,502);
        assertEquals(Action.PREVIOUS,k.actionFor(0,501));assertEquals(Action.NEXT,k.actionFor(0,502));
        assertEquals(Action.NONE,k.actionFor(0,503));
        k=k.withBinding(Action.PREVIOUS,24,501).withBinding(Action.NEXT,24,502);
        assertEquals(Action.PREVIOUS,k.actionFor(24,501));assertEquals(Action.NEXT,k.actionFor(24,502));
        assertEquals(Action.NONE,k.actionFor(24,503));
    }
    @Test public void reassignSameActionAndImmutability() {
        KeyBindings original=KeyBindings.defaults();
        KeyBindings changed=original.withBinding(Action.NEXT,93,0).withBinding(Action.NEXT,25,115);
        assertEquals(Action.NEXT,original.actionFor(93,0));assertEquals(Action.NONE,changed.actionFor(93,0));
        assertEquals(Action.NEXT,changed.actionFor(25,115));
    }
    @Test public void duplicateScanAndFallbackAreRejected() {
        KeyBindings k=KeyBindings.defaults().withBinding(Action.PREVIOUS,24,501);
        rejected(k,Action.NEXT,25,501);rejected(k,Action.NEXT,24,0);
        rejected(KeyBindings.defaults().withBinding(Action.PREVIOUS,24,0),Action.NEXT,24,502);
        rejected(KeyBindings.defaults(),Action.NEXT,92,0);
    }
    @Test public void protectedKeysCannotBeCapturedOrMatchedThroughScan() {
        KeyBindings k=KeyBindings.defaults().withBinding(Action.NEXT,0,501);
        for(int key:new int[]{3,4,26,27,187,219,220,221,223,224}) {
            assertFalse(KeyBindings.isAssignable(key,501));assertEquals(Action.NONE,k.actionFor(key,501));
        }
        for(int[] pair:new int[][]{{0,0},{-1,10},{10,-1},{65536,10},{10,65536}})
            assertFalse(KeyBindings.isAssignable(pair[0],pair[1]));
    }
    @Test public void clearAllRoundTripsWithoutRestoringDefaults() {
        KeyBindings k=KeyBindings.defaults();for(Action a:Action.values()) if(a!=Action.NONE)k=k.clearBinding(a);
        TestSharedPreferences p=new TestSharedPreferences();k.save(p);k=KeyBindings.load(p);
        for(Action a:Action.values()) assertFalse(k.isAssigned(a));
    }
    @Test public void legacyVolumeMigrationAndSavedMappingPriority() {
        TestSharedPreferences p=new TestSharedPreferences();p.put("volumeKeys",true);
        KeyBindings k=KeyBindings.load(p);
        assertEquals(Action.PREVIOUS,k.actionFor(24,115));assertEquals(Action.NEXT,k.actionFor(25,114));
        assertFalse(p.contains("volumeKeys"));assertEquals(1,p.getInt("binding.version",0));
        k=k.withBinding(Action.NEXT,131,0);k.save(p);p.put("volumeKeys",true);
        assertEquals(Action.NEXT,KeyBindings.load(p).actionFor(131,0));
        assertEquals(Action.NONE,KeyBindings.load(p).actionFor(25,0));
    }
    @Test public void corruptedDuplicateMappingsFailSafely() {
        TestSharedPreferences p=new TestSharedPreferences();KeyBindings.defaults().save(p);
        p.put("binding.NEXT",92);
        assertEquals(Action.NEXT,KeyBindings.load(p).actionFor(93,0));
        p.put("binding.NEXT",26);
        assertEquals(Action.NONE,KeyBindings.load(p).actionFor(26,0));
    }
    @Test public void saveHasOnlyNumericBindingsAndNoContent() {
        TestSharedPreferences p=new TestSharedPreferences();
        KeyBindings.defaults().withBinding(Action.REFRESH,0,500).save(p);
        assertEquals(9,p.dump().size());
        for(Object v:p.dump().values())assertTrue(v instanceof Integer);
        assertEquals(Action.REFRESH,KeyBindings.load(p).actionFor(0,500));
    }
    private static void rejected(KeyBindings k,Action a,int key,int scan) {
        try{k.withBinding(a,key,scan);fail();}catch(IllegalArgumentException expected){}
    }
}
