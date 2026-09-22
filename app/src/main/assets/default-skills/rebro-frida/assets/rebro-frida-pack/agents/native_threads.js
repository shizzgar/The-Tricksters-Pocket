// Original Rebro Pack. MIT.
Rebro.module('native_threads', false, function (o, c) {
const items=Process.enumerateThreads();c.emit('threads',{total:items.length,items:c.list(items).map(t=>({id:t.id,name:t.name||null,state:t.state}))});
});

