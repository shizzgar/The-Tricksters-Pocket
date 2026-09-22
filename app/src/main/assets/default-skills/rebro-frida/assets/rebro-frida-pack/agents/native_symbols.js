// Original Rebro Pack. MIT.
Rebro.module('native_symbols', false, function (o, c) {
c.onModule(o.module_pattern||'^libc\\.so$',m=>{const rx=c.regex(o.symbol_pattern,'.*'),items=m.enumerateSymbols().filter(e=>rx.test(e.name));c.emit('symbols',{module:m.name,total:items.length,items:c.list(items).map(e=>({name:e.name,type:e.type,address:String(e.address),size:e.size}))});});
});

