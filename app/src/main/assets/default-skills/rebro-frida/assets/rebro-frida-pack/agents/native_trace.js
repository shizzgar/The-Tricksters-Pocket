// Original Rebro Pack. MIT.
Rebro.module('native_trace', false, function (o, c) {
c.require(o.symbol||o.offset,'Provide symbol or hex offset');c.onModule('.*',m=>{
if(m.name!==o.module)return;const address=c.resolveTarget(m,o);
c.attach(address,{onEnter(args){this.start=Date.now();c.emit('enter',{module:m.name,address:String(address),args:[0,1,2,3].map(i=>String(args[i])),backtrace:c.limits.backtrace?c.backtrace(this.context):undefined});},
onLeave(ret){c.emit('leave',{module:m.name,address:String(address),retval:String(ret),elapsed_ms:Date.now()-this.start});}});
});
});

