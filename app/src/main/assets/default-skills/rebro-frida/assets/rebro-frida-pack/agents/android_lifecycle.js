/* Rebro Pack: android_lifecycle. Original MIT code; requires external Java bridge. */
Rebro.module('android_lifecycle', true, function(o,c) {
'use strict';
let n=0;for(const method of ['onResume','onPause','onDestroy'])n+=c.tryJava('android.app.Activity',method,function(){c.emit('activity',{method,class:this.$className});});c.require(n>0,'Activity methods unavailable');
});

