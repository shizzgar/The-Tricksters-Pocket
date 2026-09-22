/* Rebro Pack: java_classload. Original MIT code; requires external Java bridge. */
Rebro.module('java_classload', true, function(o,c) {
'use strict';
const rx=c.regex(o.pattern,'^com\\.');
c.hookJava('java.lang.ClassLoader','loadClass',function(a,s){s.name=String(a[0]);},function(a,r,s){if(rx.test(s.name))c.emit('class_loaded',{name:c.clip(s.name),loader:this.$className});});
});

