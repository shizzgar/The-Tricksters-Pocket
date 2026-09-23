// Original Rebro Pack. MIT.
Rebro.module('native_pthreads', false, function (o, c) {
c.onModule('^libc\\.so$',m=>{const p=m.findExportByName('pthread_create');c.require(p,'pthread_create missing');c.attach(p,{onEnter(args){this.rec={entry:String(args[2]),symbol:DebugSymbol.fromAddress(args[2]).toString()};},onLeave(ret){c.emit('pthread_create',{...this.rec,result:ret.toInt32()});}});});
});

