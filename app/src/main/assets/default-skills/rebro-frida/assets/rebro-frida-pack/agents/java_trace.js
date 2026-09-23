/* Rebro Pack: java_trace. Original MIT code; requires external Java bridge. */
Rebro.module('java_trace', true, function(o,c) {
'use strict';
let factory=Java;
if(o.loader_class){const loaders=Java.enumerateClassLoadersSync().filter(l=>l.$className===o.loader_class);c.require(loaders.length===1,'loader_class must match exactly one loader; inspect java_loaders');factory=Java.ClassFactory.get(loaders[0]);}
c.hookJava(o.class_name,o.method,function(a,s){s.start=Date.now();s.args=a.map(c.value);},function(a,r,s,t){c.emit('java_call',{class:o.class_name,method:o.method,signature:t,args:s.args,result:c.value(r),elapsed_ms:Date.now()-s.start});},o.signature,factory);
});

