/* Rebro Pack: android_assets. Original MIT code; requires external Java bridge. */
Rebro.module('android_assets', true, function(o,c) {
'use strict';
c.hookJava('android.content.res.AssetManager','open',function(a){c.emit('asset_open',{name:c.clip(a[0])});});
});

