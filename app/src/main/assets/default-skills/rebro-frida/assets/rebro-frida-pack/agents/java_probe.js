/* Rebro Pack: java_probe. Original MIT code; requires external Java bridge. */
Rebro.module('java_probe', true, function(o,c) {
'use strict';
const Build=Java.use('android.os.Build'), V=Java.use('android.os.Build$VERSION');
c.emit('java_environment',{available:Java.available,android_version:Java.androidVersion,api:V.SDK_INT.value,model:c.clip(Build.MODEL.value),loader:Java.classFactory.loader ? Java.classFactory.loader.$className : null});
});

