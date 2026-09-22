/* Rebro Pack: java_methods. Original MIT code; requires external Java bridge. */
Rebro.module('java_methods', true, function(o,c) {
'use strict';
const k=Java.use(o.class_name), rx=c.regex(o.pattern,'.*'), methods=k.class.getDeclaredMethods(); let shown=0;
for(let i=0;i<methods.length && shown<c.limits.max_items;i++){const s=String(methods[i].toString()); if(rx.test(s)){c.emit('method',{declaration:c.clip(s)});shown++;}}
c.emit('inventory_complete',{declared:methods.length,shown});
});

