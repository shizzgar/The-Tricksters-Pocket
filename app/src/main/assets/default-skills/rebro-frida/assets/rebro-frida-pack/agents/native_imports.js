// Original Rebro Pack. MIT.
Rebro.module('native_imports', false, function (o, c) {
c.onModule(o.module_pattern||'^libc\\.so$',m=>{const rx=c.regex(o.symbol_pattern,'.*'),items=m.enumerateImports().filter(e=>rx.test(e.name));c.emit('imports',{module:m.name,total:items.length,items:c.list(items).map(e=>({name:e.name,module:e.module||null,type:e.type,address:e.address?String(e.address):null}))});});
});

