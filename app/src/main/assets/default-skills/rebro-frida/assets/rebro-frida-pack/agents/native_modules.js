// Original Rebro Pack. MIT.
Rebro.module('native_modules', false, function (o, c) {
const rx=c.regex(o.pattern,'.*'),items=Process.enumerateModules().filter(m=>rx.test(m.name));c.emit('modules',{total:items.length,items:c.list(items).map(m=>({name:m.name,path:m.path,base:String(m.base),size:m.size}))});
});

