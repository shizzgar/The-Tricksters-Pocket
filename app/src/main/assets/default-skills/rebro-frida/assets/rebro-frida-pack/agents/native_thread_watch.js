// Original Rebro Pack. MIT.
Rebro.module('native_thread_watch', false, function (o, c) {
c.require(typeof Process.attachThreadObserver==='function','Thread observer unavailable');const obs=Process.attachThreadObserver({onAdded(t){c.emit('thread_added',{id:t.id,name:t.name||null});},onRemoved(t){c.emit('thread_removed',{id:t.id,name:t.name||null});}});c.addCleanup(()=>obs.detach());
});

