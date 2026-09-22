/* Rebro Pack: java_classes. Original MIT code; requires external Java bridge. */
Rebro.module('java_classes', true, function(o,c) {
'use strict';
const rx=c.regex(o.pattern,'^com\\.'); let count=0, matched=0;
for(const name of Java.enumerateLoadedClassesSync()) { if(!rx.test(name)) continue; matched++; if(count++<c.limits.max_items) c.emit('class',{name:c.clip(name)}); }
c.emit('inventory_complete',{matched,shown:Math.min(count,c.limits.max_items)});
});

