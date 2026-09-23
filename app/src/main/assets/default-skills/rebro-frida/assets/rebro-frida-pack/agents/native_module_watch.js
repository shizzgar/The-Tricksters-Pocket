// Original Rebro Pack. MIT.
Rebro.module('native_module_watch', false, function (o, c) {
c.onModule(o.pattern||'.*',m=>c.emit('module_loaded',{name:m.name,path:m.path,base:String(m.base),size:m.size}));
});

