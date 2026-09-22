/* Rebro Pack: java_threads. Original MIT code; requires external Java bridge. */
Rebro.module('java_threads', true, function(o,c) {
'use strict';
c.hookJava('java.lang.Thread','start',function(){c.emit('java_thread_start',{name:c.clip(this.getName()),id:String(this.getId())});});
});

