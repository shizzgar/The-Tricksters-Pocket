// Original Rebro Pack. MIT.
Rebro.module('native_scan', false, function (o, c) {
const size=o.length===undefined?65536:o.length;c.require(Number.isInteger(size)&&size>0&&size<=1048576,'length must be 1..1048576');const m=Process.getModuleByName(o.module),p=c.resolveTarget(m,o),r=Process.findRangeByAddress(p);c.require(r&&r.protection[0]==='r'&&p.add(size).compare(r.base.add(r.size))<=0&&p.add(size).compare(m.base.add(m.size))<=0,'Scan crosses readable/module boundary');let count=0,active=true;c.addCleanup(()=>{active=false;});Memory.scan(p,size,o.pattern,{onMatch(address,length){if(!active)return 'stop';c.emit('match',{module:m.name,address:String(address),offset:String(address.sub(m.base)),length});if(++count>=c.limits.max_items)return 'stop';},onError(reason){c.emit('hook_error',{error:reason});},onComplete(){if(active)c.emit('scan_complete',{matches:count});}});
});

