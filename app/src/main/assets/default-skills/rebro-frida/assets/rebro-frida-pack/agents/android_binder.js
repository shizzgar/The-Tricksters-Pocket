/* Rebro Pack: android_binder. Original MIT code; requires external Java bridge. */
Rebro.module('android_binder', true, function(o,c) {
'use strict';
c.hookJava('android.os.BinderProxy','transact',function(a){c.emit('binder_transact',{code:a[0],flags:a[3]});});
});

