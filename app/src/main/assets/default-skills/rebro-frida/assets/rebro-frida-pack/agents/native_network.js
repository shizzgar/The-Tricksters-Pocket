// Original Rebro Pack. MIT.
Rebro.module('native_network', false, function (o, c) {
function sockaddr(p,len){try{if(p.isNull()||len<2)return null;const family=p.readU16();const port=()=>((p.add(2).readU8()<<8)|p.add(3).readU8());if(family===2&&len>=8)return {family:'IPv4',port:port(),ip:[4,5,6,7].map(i=>p.add(i).readU8()).join('.')};if(family===10&&len>=24)return {family:'IPv6',port:port(),ip:[8,10,12,14,16,18,20,22].map(i=>((p.add(i).readU8()<<8)|p.add(i+1).readU8()).toString(16)).join(':')};return {family};}catch(_){return null;}}
c.onModule('^libc\\.so$',m=>{let count=0;for(const [symbol,a,l] of [['connect',1,2],['sendto',4,5]]){const p=m.findExportByName(symbol);if(!p)continue;count++;c.attach(p,{onEnter(args){this.rec={symbol,fd:args[0].toInt32(),destination:sockaddr(args[a],args[l].toInt32())};},onLeave(ret){if(this.rec.destination)c.emit('socket_destination',{...this.rec,result:ret.toInt32()});}});}c.require(count>0,'No network exports');});
});

