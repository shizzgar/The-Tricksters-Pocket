/* Rebro Pack: android_sqlite. Original MIT code; requires external Java bridge. */
Rebro.module('android_sqlite', true, function(o,c) {
'use strict';
let n=0;for(const method of ['execSQL','rawQuery'])n+=c.tryJava('android.database.sqlite.SQLiteDatabase',method,function(a){c.emit('sqlite',{method,sql:c.value(a[0]),bind_count:a.length>1&&a[1]!==null?c.byteLength(a[1]):0});});c.require(n>0,'SQLiteDatabase methods unavailable');
});

