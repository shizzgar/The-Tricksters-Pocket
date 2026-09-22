// Original Rebro Pack. MIT.
Rebro.module('native_dlopen', false, function (o, c) {
let count=0;for(const symbol of ['dlopen','android_dlopen_ext']){const p=Module.findGlobalExportByName(symbol);if(!p)continue;count++;c.attach(p,{onEnter(args){this.path=c.cstring(args[0]);},onLeave(ret){c.emit('dlopen',{symbol,path:this.path,success:!ret.isNull(),handle:String(ret)});}});}c.require(count>0,'No dlopen exports');
});

