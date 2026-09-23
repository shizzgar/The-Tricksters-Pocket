// Original Rebro Pack. MIT.
Rebro.module('native_memory', false, function (o, c) {
const size=o.length===undefined?64:o.length;c.require(Number.isInteger(size)&&size>0&&size<=4096,'length must be 1..4096');const m=Process.getModuleByName(o.module),p=c.resolveTarget(m,o),r=Process.findRangeByAddress(p);c.require(r&&r.protection[0]==='r'&&p.add(size).compare(r.base.add(r.size))<=0&&p.add(size).compare(m.base.add(m.size))<=0,'Read crosses readable/module boundary');const bytes=new Uint8Array(p.readByteArray(size));c.emit('memory',{module:m.name,address:String(p),offset:o.offset,length:size,hex:Array.from(bytes,x=>x.toString(16).padStart(2,'0')).join('')});
});

