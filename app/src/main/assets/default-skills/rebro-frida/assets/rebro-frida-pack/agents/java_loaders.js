/* Rebro Pack: java_loaders. Original MIT code; requires external Java bridge. */
Rebro.module('java_loaders', true, function(o,c) {
'use strict';
let count=0; Java.enumerateClassLoaders({onMatch(loader){if(count++<c.limits.max_items)c.emit('class_loader',{class:loader.$className,description:c.clip(loader.toString())});},onComplete(){c.emit('inventory_complete',{loaders:count,shown:Math.min(count,c.limits.max_items)});}});
});

