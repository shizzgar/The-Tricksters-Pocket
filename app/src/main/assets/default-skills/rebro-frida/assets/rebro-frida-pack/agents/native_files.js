// Original Rebro Pack. MIT.
Rebro.module('native_files', false, function (o, c) {
const rx=c.regex(o.path_pattern,'.*');c.onModule('^libc\\.so$',m=>{let count=0;for(const [symbol,i] of [['open',0],['openat',1]]){const p=m.findExportByName(symbol);if(!p)continue;count++;c.attach(p,{onEnter(args){const path=c.cstring(args[i]);this.rec=path&&rx.test(path)?{symbol,path,flags:args[i+1].toInt32()}:null;},onLeave(ret){if(this.rec)c.emit('file_open',{...this.rec,fd:ret.toInt32()});}});}c.require(count>0,'No open/openat exports');});
});

