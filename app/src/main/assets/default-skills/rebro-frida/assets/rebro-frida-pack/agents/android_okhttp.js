/* Rebro Pack: android_okhttp. Original MIT code; requires external Java bridge. */
Rebro.module('android_okhttp', true, function(o,c) {
'use strict';
c.hookJava(o.class_name || 'okhttp3.OkHttpClient','newCall',function(a){const r=a[0];c.emit('okhttp_request',{method:c.clip(r.method()),url:c.url(r.url().toString())});});
});

