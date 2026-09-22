/* Rebro Pack: java_dex. Original MIT code; requires external Java bridge. */
Rebro.module('java_dex', true, function(o,c) {
'use strict';
let n=0;
n+=c.tryJava('dalvik.system.DexClassLoader','$init',function(a){c.emit('dex_loader',{class:this.$className,dex_path:c.clip(a[0]),library_path:a[2]===null?null:c.clip(a[2])});});
n+=c.tryJava('dalvik.system.InMemoryDexClassLoader','$init',function(a,s,t){c.emit('memory_dex_loader',{class:this.$className,signature:t,argc:a.length});});
c.require(n>0,'No supported DEX constructors');
});

