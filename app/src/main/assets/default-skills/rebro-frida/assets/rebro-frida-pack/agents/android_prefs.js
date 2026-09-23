/* Rebro Pack: android_prefs. Original MIT code; requires external Java bridge. */
Rebro.module('android_prefs', true, function(o,c) {
'use strict';
let n=0;
for(const method of ['getString','getInt','getLong','getBoolean','getFloat','contains'])n+=c.tryJava('android.app.SharedPreferencesImpl',method,function(a){c.emit('preference',{method,key:c.clip(a[0])});});
for(const method of ['putString','putInt','putLong','putBoolean','remove'])n+=c.tryJava('android.app.SharedPreferencesImpl$EditorImpl',method,function(a){c.emit('preference',{method,key:c.clip(a[0])});});
c.require(n>0,'SharedPreferencesImpl unavailable; DataStore/custom wrappers need their own hooks');
});

