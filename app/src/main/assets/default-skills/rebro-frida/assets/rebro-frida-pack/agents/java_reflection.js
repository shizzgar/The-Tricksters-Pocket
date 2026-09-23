/* Rebro Pack: java_reflection. Original MIT code; requires external Java bridge. */
Rebro.module('java_reflection', true, function(o,c) {
'use strict';
const rx=c.regex(o.pattern,'^com\\.');
c.hookJava('java.lang.reflect.Method','invoke',function(a){const name=String(this.getDeclaringClass().getName());if(rx.test(name))c.emit('reflection',{class:c.clip(name),method:c.clip(this.getName()),argc:a[1]===null?0:a[1].length});});
});

