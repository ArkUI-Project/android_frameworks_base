/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ComboStoreTest {
    private SharedPreferences preferences;
    private ComboStore store;
    private static final String LEGACY = "Combo 1";

    @Before public void setUp() {
        preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("combo_store_tests",Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        store = new ComboStore(preferences);
    }
    @After public void cleanUp() { preferences.edit().clear().commit(); }
    private static JSONArray actions(int coordinate) throws Exception {
        return new JSONArray("[[" + coordinate + ",4000,5000,6000,120,500]]");
    }

    @Test public void saveMultipleAndReopen() throws Exception {
        var first = store.save("game",1,"First",actions(1000),LEGACY);
        var second = store.save("game",1,"Second",actions(2000),LEGACY);
        assertNotEquals(first.id(),second.id());
        assertEquals(2,new ComboStore(preferences).list("game",1,LEGACY).size());
        assertArrayEquals(first.actions(),store.list("game",1,LEGACY).get(0).actions());
        assertArrayEquals(second.actions(),store.list("game",1,LEGACY).get(1).actions());
    }
    @Test public void migrateSingleSlotOnce() throws Exception {
        preferences.edit().putString("combo:game:1",actions(1000).toString()).putInt("repeat",3).commit();
        var migrated = store.list("game",1,LEGACY);
        assertEquals(1,migrated.size());
        assertEquals(LEGACY,migrated.get(0).name());
        assertEquals(3,migrated.get(0).repeat());
        assertFalse(preferences.contains("combo:game:1"));
        assertEquals(1,new ComboStore(preferences).list("game",1,LEGACY).size());
        store.save("game",1,"Second",actions(2000),LEGACY);
        assertEquals(2,store.list("game",1,LEGACY).size());
    }
    @Test public void isolateGamesAndOrientations() throws Exception {
        var portrait = store.save("first",1,"Portrait",actions(1000),LEGACY);
        store.save("first",2,"Landscape",actions(2000),LEGACY);
        store.save("second",1,"Other game",actions(3000),LEGACY);
        store.delete("first",1,portrait.id(),LEGACY);
        assertTrue(store.list("first",1,LEGACY).isEmpty());
        assertEquals("Landscape",store.list("first",2,LEGACY).get(0).name());
        assertEquals("Other game",store.list("second",1,LEGACY).get(0).name());
    }
    @Test public void editOnlyTheChosenRecording() throws Exception {
        var first = store.save("game",1,"First",actions(1000),LEGACY);
        var second = store.save("game",1,"Second",actions(2000),LEGACY);
        assertTrue(store.update("game",1,second.id(),"  新连招  ",5,LEGACY));
        var saved = new ComboStore(preferences).list("game",1,LEGACY);
        assertEquals(first.name(),saved.get(0).name());
        assertEquals(first.repeat(),saved.get(0).repeat());
        assertEquals(second.id(),saved.get(1).id());
        assertEquals("新连招",saved.get(1).name());
        assertEquals(5,saved.get(1).repeat());
        assertArrayEquals(second.actions(),saved.get(1).actions());
    }
    @Test public void deletionDoesNotResurrectLegacySlot() throws Exception {
        preferences.edit().putString("combo:game:1",actions(1000).toString()).commit();
        var migrated = store.list("game",1,LEGACY).get(0);
        store.delete("game",1,migrated.id(),LEGACY);
        assertTrue(new ComboStore(preferences).list("game",1,LEGACY).isEmpty());
        assertTrue(preferences.contains("combos:game:1"));
    }
    @Test public void rejectInvalidRecordingsWithoutReplacingExistingOnes() throws Exception {
        var original = store.save("game",1,"Existing",actions(1000),LEGACY);
        for (String invalid : new String[]{"[]","[[10001,0,0,0,100,500]]","[[0,0,0,0,-1,500]]","[[0,0]]"}) {
            assertThrows(IllegalArgumentException.class,() -> store.save("game",1,"Invalid",new JSONArray(invalid),LEGACY));
        }
        assertFalse(store.update("game",1,original.id()," ",3,LEGACY));
        assertFalse(store.update("game",1,original.id(),"Existing",-1,LEGACY));
        assertEquals(1,store.list("game",1,LEGACY).size());
        assertArrayEquals(original.actions(),store.list("game",1,LEGACY).get(0).actions());
    }
    @Test public void keepMalformedLegacyData() {
        preferences.edit().putString("combo:game:1","broken").commit();
        assertTrue(store.list("game",1,LEGACY).isEmpty());
        assertEquals("broken",preferences.getString("combo:game:1",null));
        assertFalse(preferences.contains("combos:game:1"));
    }
    @Test public void retainLongRecordingsAndUnrestrictedRepeats() throws Exception {
        JSONArray recorded = new JSONArray();
        for (int i = 0; i < 100; i++) recorded.put(new JSONArray("[1000,4000,5000,6000,5000,6000]"));
        preferences.edit().putInt("repeat",25).commit();
        var combo = store.save("game",1,"Long recording",recorded,LEGACY);
        assertEquals(100,combo.count());
        assertEquals(25,combo.repeat());
        assertEquals(5000,combo.actions()[4]);
        assertEquals(6000,combo.actions()[5]);
        assertTrue(store.update("game",1,combo.id(),combo.name(),0,LEGACY));
        assertEquals(0,new ComboStore(preferences).list("game",1,LEGACY).get(0).repeat());
        assertTrue(store.update("game",1,combo.id(),combo.name(),Integer.MAX_VALUE,LEGACY));
        assertEquals(Integer.MAX_VALUE,new ComboStore(preferences).list("game",1,LEGACY).get(0).repeat());
    }
}
